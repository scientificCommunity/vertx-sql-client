/*
 * Copyright (C) 2017 Julien Viet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.vertx.sqlclient.impl;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.impl.ContextInternal;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlResult;
import io.vertx.sqlclient.Tuple;
import io.vertx.sqlclient.impl.command.BiCommand;
import io.vertx.sqlclient.impl.command.CommandScheduler;
import io.vertx.sqlclient.impl.command.ExtendedQueryCommand;
import io.vertx.sqlclient.impl.command.PrepareStatementCommand;
import io.vertx.sqlclient.impl.command.SimpleQueryCommand;
import io.vertx.sqlclient.impl.tracing.QueryTracer;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collector;

/**
 * Executes query.
 */
class QueryExecutor<T, R extends SqlResultBase<T>, L extends SqlResult<T>> {

  private final QueryTracer tracer;
  private final Function<T, R> factory;
  private final Collector<Row, ?, T> collector;

  public QueryExecutor(QueryTracer tracer,
                       Function<T, R> factory,
                       Collector<Row, ?, T> collector) {
    this.tracer = tracer;
    this.factory = factory;
    this.collector = collector;
  }

  QueryTracer tracer() {
    return tracer;
  }

  private QueryResultBuilder<T, R, L> createHandler(Promise<L> promise, Object payload) {
    return new QueryResultBuilder<>(factory, tracer, payload, promise);
  }

  void executeSimpleQuery(CommandScheduler scheduler,
                          String sql,
                          boolean autoCommit,
                          boolean singleton,
                          Promise<L> promise) {
    ContextInternal context = (ContextInternal) promise.future().context();
    Object payload;
    if (tracer != null) {
      payload = tracer.sendRequest(context, sql);
    } else {
      payload = null;
    }
    QueryResultBuilder handler = createHandler(promise, payload);
    scheduler.schedule(new SimpleQueryCommand<>(sql, singleton, autoCommit, collector, handler), handler);
  }

  QueryResultBuilder<T, R, L> executeExtendedQuery(CommandScheduler scheduler,
                                                   PreparedStatement preparedStatement,
                                                   boolean autoCommit,
                                                   Tuple arguments,
                                                   int fetch,
                                                   String cursorId,
                                                   boolean suspended,
                                                   Promise<L> promise) {
    ContextInternal context = (ContextInternal) promise.future().context();
    Object payload;
    if (tracer != null) {
      payload = tracer.sendRequest(context, preparedStatement.sql(), arguments);
    } else {
      payload = null;
    }
    QueryResultBuilder handler = createHandler(promise, payload);
    String msg = preparedStatement.prepare((TupleInternal) arguments);
    if (msg != null) {
      handler.fail(msg);
      return null;
    }
    ExtendedQueryCommand<T> cmd = ExtendedQueryCommand.createQuery(
      preparedStatement,
      arguments,
      fetch,
      cursorId,
      suspended,
      autoCommit,
      collector,
      handler);
    scheduler.schedule(cmd, handler);
    return handler;
  }

  void executeExtendedQuery(CommandScheduler scheduler, String sql, boolean autoCommit, Tuple arguments, Promise<L> promise) {
    ContextInternal context = (ContextInternal) promise.future().context();
    Object payload;
    if (tracer != null) {
      payload = tracer.sendRequest(context, sql, arguments);
    } else {
      payload = null;
    }
    QueryResultBuilder handler = this.createHandler(promise, payload);
    BiCommand<PreparedStatement, Boolean> cmd = new BiCommand<>(new PrepareStatementCommand(sql, true), ps -> {
      String msg = ps.prepare((TupleInternal) arguments);
      if (msg != null) {
        return Future.failedFuture(msg);
      }
      return Future.succeededFuture(createExtendedQueryCommand(ps, autoCommit, arguments, handler));
    });
    scheduler.schedule(cmd, handler);
  }

  private ExtendedQueryCommand<T> createExtendedQueryCommand(PreparedStatement preparedStatement,
                                                             boolean autoCommit,
                                                             Tuple tuple,
                                                             QueryResultBuilder<T, R, L> handler) {
    return ExtendedQueryCommand.createQuery(
      preparedStatement,
      tuple,
      autoCommit,
      collector,
      handler);
  }

  void executeBatchQuery(CommandScheduler scheduler,
                         PreparedStatement preparedStatement,
                         boolean autoCommit,
                         List<Tuple> batch,
                         Promise<L> promise) {
    ContextInternal context = (ContextInternal) promise.future().context();
    Object payload;
    if (tracer != null) {
      payload = tracer.sendRequest(context, preparedStatement.sql(), batch);
    } else {
      payload = null;
    }
    QueryResultBuilder handler = createHandler(promise, payload);
    for  (Tuple args : batch) {
      String msg = preparedStatement.prepare((TupleInternal)args);
      if (msg != null) {
        handler.fail(msg);
        return;
      }
    }
    ExtendedQueryCommand<T> cmd = ExtendedQueryCommand.createBatch(preparedStatement, batch, autoCommit, collector, handler);
    scheduler.schedule(cmd, handler);
  }

  void executeBatchQuery(CommandScheduler scheduler, String sql, boolean autoCommit, List<Tuple> batch, Promise<L> promise) {
    ContextInternal context = (ContextInternal) promise.future().context();
    Object payload;
    if (tracer != null) {
      payload = tracer.sendRequest(context, sql, batch);
    } else {
      payload = null;
    }
    QueryResultBuilder handler = createHandler(promise, payload);
    BiCommand<PreparedStatement, Boolean> cmd = new BiCommand<>(new PrepareStatementCommand(sql, true), ps -> {
      for  (Tuple args : batch) {
        String msg = ps.prepare((TupleInternal) args);
        if (msg != null) {
          return Future.failedFuture(msg);
        }
      }
      return Future.succeededFuture(createBatchQueryCommand(ps, autoCommit, batch, handler));
    });
    scheduler.schedule(cmd, handler);
  }

  private ExtendedQueryCommand<T> createBatchQueryCommand(PreparedStatement preparedStatement,
                                                          boolean autoCommit,
                                                          List<Tuple> argsList,
                                                          QueryResultBuilder<T, R, L> handler) {
    return ExtendedQueryCommand.createBatch(preparedStatement, argsList, autoCommit, collector, handler);
  }
}