/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.statefun.flink.core.functions;

import java.util.Map;
import java.util.Objects;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.common.typeutils.base.LongSerializer;
import org.apache.flink.runtime.state.KeyedStateBackend;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.runtime.state.internal.InternalListState;
import org.apache.flink.statefun.flink.core.StatefulFunctionsConfig;
import org.apache.flink.statefun.flink.core.StatefulFunctionsUniverse;
import org.apache.flink.statefun.flink.core.StatefulFunctionsUniverses;
import org.apache.flink.statefun.flink.core.backpressure.BackPressureValve;
import org.apache.flink.statefun.flink.core.backpressure.ThresholdBackPressureValve;
import org.apache.flink.statefun.flink.core.common.MailboxExecutorFacade;
import org.apache.flink.statefun.flink.core.message.Message;
import org.apache.flink.statefun.flink.core.message.MessageFactory;
import org.apache.flink.statefun.flink.core.types.DynamicallyRegisteredTypes;
import org.apache.flink.statefun.sdk.io.EgressIdentifier;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.MailboxExecutor;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.ProcessingTimeService;
import org.apache.flink.util.OutputTag;

public class FunctionGroupOperator extends AbstractStreamOperator<Message>
    implements OneInputStreamOperator<Message, Message> {

  private static final long serialVersionUID = 1L;

  // -- configuration
  private final Map<EgressIdentifier<?>, OutputTag<Object>> sideOutputs;

  private final StatefulFunctionsConfig configuration;

  // -- runtime
  private transient Reductions reductions;
  private transient MailboxExecutor mailboxExecutor;
  private transient BackPressureValve backPressureValve;

  FunctionGroupOperator(
      Map<EgressIdentifier<?>, OutputTag<Object>> sideOutputs,
      StatefulFunctionsConfig configuration,
      MailboxExecutor mailboxExecutor,
      ChainingStrategy chainingStrategy,
      ProcessingTimeService processingTimeService) {
    this.sideOutputs = Objects.requireNonNull(sideOutputs);
    this.configuration = Objects.requireNonNull(configuration);
    this.mailboxExecutor = Objects.requireNonNull(mailboxExecutor);
    this.chainingStrategy = chainingStrategy;
    this.processingTimeService = processingTimeService;
  }

  // ------------------------------------------------------------------------------------------------------------------
  // Operator API
  // ------------------------------------------------------------------------------------------------------------------

  @Override
  public void processElement(StreamRecord<Message> record) throws InterruptedException {
    while (backPressureValve.shouldBackPressure()) {
      mailboxExecutor.yield();
    }
    reductions.apply(record.getValue());
  }

  @Override
  public void initializeState(StateInitializationContext context) throws Exception {
    super.initializeState(context);

    final StatefulFunctionsUniverse statefulFunctionsUniverse =
        statefulFunctionsUniverse(configuration);

    final TypeSerializer<Message> envelopeSerializer =
        getOperatorConfig().getTypeSerializerIn(0, getContainingTask().getUserCodeClassLoader());
    final MapStateDescriptor<Long, Message> asyncOperationStateDescriptor =
        new MapStateDescriptor<>(
            "asyncOperations", LongSerializer.INSTANCE, envelopeSerializer.duplicate());
    final ListStateDescriptor<Message> delayedMessageStateDescriptor =
        new ListStateDescriptor<>(
            FlinkStateDelayedMessagesBuffer.BUFFER_STATE_NAME, envelopeSerializer.duplicate());
    final MapState<Long, Message> asyncOperationState =
        context.getKeyedStateStore().getMapState(asyncOperationStateDescriptor);

    Objects.requireNonNull(mailboxExecutor, "MailboxExecutor is unexpectedly NULL");

    this.backPressureValve =
        new ThresholdBackPressureValve(configuration.getMaxAsyncOperationsPerTask());

    //
    // the core logic of applying messages to functions.
    //
    this.reductions =
        Reductions.create(
            backPressureValve,
            statefulFunctionsUniverse,
            getRuntimeContext(),
            getKeyedStateBackend(),
            new FlinkTimerServiceFactory(
                super.getTimeServiceManager().orElseThrow(IllegalStateException::new)),
            delayedMessagesBufferState(delayedMessageStateDescriptor),
            sideOutputs,
            output,
            MessageFactory.forType(statefulFunctionsUniverse.messageFactoryType()),
            new MailboxExecutorFacade(mailboxExecutor, "Stateful Functions Mailbox"),
            getRuntimeContext().getMetricGroup().addGroup("functions"),
            asyncOperationState);

    //
    // De-multiplex legacy remote function state in versions <= 2.1.x
    // TODO backwards compatibility path for 2.1.x supported only in 2.2.x, remove for 2.3.x
    //
    if (configuration.shouldMigrateLegacyRemoteFnState()) {
      final DynamicallyRegisteredTypes dynamicallyRegisteredTypes =
          new DynamicallyRegisteredTypes(statefulFunctionsUniverse.types());
      RemoteFunctionStateMigrator.apply(
          statefulFunctionsUniverse.functions(),
          getKeyedStateBackend(),
          dynamicallyRegisteredTypes.registerType(String.class),
          dynamicallyRegisteredTypes.registerType(byte[].class));
    }

    //
    // expire all the pending async operations.
    //
    AsyncOperationFailureNotifier.fireExpiredAsyncOperations(
        asyncOperationStateDescriptor, reductions, getKeyedStateBackend());
  }

  @Override
  public void snapshotState(StateSnapshotContext context) throws Exception {
    super.snapshotState(context);
    reductions.snapshotAsyncOperations();
  }

  // ------------------------------------------------------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------------------------------------------------------

  private InternalListState<String, Long, Message> delayedMessagesBufferState(
      ListStateDescriptor<Message> delayedMessageStateDescriptor) {
    try {
      KeyedStateBackend<String> keyedStateBackend = getKeyedStateBackend();
      return (InternalListState<String, Long, Message>)
          keyedStateBackend.getOrCreateKeyedState(
              LongSerializer.INSTANCE, delayedMessageStateDescriptor);
    } catch (Exception e) {
      throw new RuntimeException("Error registered Flink state for delayed messages buffer.", e);
    }
  }

  private StatefulFunctionsUniverse statefulFunctionsUniverse(
      StatefulFunctionsConfig configuration) {
    final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    return StatefulFunctionsUniverses.get(classLoader, configuration);
  }
}
