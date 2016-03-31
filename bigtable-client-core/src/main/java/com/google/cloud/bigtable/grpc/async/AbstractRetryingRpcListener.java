/*
 * Copyright 2015 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.bigtable.grpc.async;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.api.client.util.BackOff;
import com.google.api.client.util.Sleeper;
import com.google.cloud.bigtable.config.Logger;
import com.google.cloud.bigtable.config.RetryOptions;
import com.google.cloud.bigtable.grpc.io.CancellationToken;
import com.google.cloud.bigtable.grpc.scanner.BigtableRetriesExhaustedException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.SettableFuture;

import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * A {@link AsyncFunction} that retries a {@link BigtableAsyncRpc} request.
 */
public abstract class AbstractRetryingRpcListener<RequestT, ResponseT, ResultT>
    extends ClientCall.Listener<ResponseT> implements Runnable {

  protected final static Logger LOG = new Logger(AbstractRetryingRpcListener.class);

  private final RequestT request;

  @VisibleForTesting
  BackOff currentBackoff;
  @VisibleForTesting
  Sleeper sleeper = Sleeper.DEFAULT;

  protected final BigtableAsyncRpc<RequestT, ResponseT> rpc;
  protected final RetryOptions retryOptions;
  protected final ScheduledExecutorService retryExecutorService;
  protected final SettableFuture<ResultT> completionFuture;
  private int failedCount;
  protected ClientCall<RequestT, ResponseT> call;

  public AbstractRetryingRpcListener(
      RetryOptions retryOptions,
      RequestT request,
      BigtableAsyncRpc<RequestT, ResponseT> retryableRpc,
      ScheduledExecutorService executorService) {
    this(retryOptions, request, retryableRpc, executorService, SettableFuture.<ResultT>create());
  }

  public AbstractRetryingRpcListener(
          RetryOptions retryOptions,
          RequestT request,
          BigtableAsyncRpc<RequestT, ResponseT> retryableRpc,
          ScheduledExecutorService retryExecutorService,
          SettableFuture<ResultT> completionFuture) {
    this.retryOptions = retryOptions;
    this.request = request;
    this.rpc = retryableRpc;
    this.retryExecutorService = retryExecutorService;
    this.completionFuture = completionFuture;
  }

  @Override
  public void onClose(Status status, Metadata trailers) {
    if (status.isOk()) {
      onOK();
    } else {
      Status.Code code = status.getCode();
      if (retryOptions.enableRetries() && retryOptions.isRetryable(code)
          && rpc.isRetryable(request)) {
        backOffAndRetry(status);
      } else {
        completionFuture.setException(status.asRuntimeException());
      }
    }
  }

  protected abstract void onOK();

  private void backOffAndRetry(Status status) {
    long nextBackOff = getNextBackoff();
    failedCount += 1;
    if (nextBackOff == BackOff.STOP) {
      String message = String.format("Exhausted retries after %d failures.", failedCount);
      StatusRuntimeException cause = status.asRuntimeException();
      completionFuture.setException(new BigtableRetriesExhaustedException(message, cause));
      return;
    }
    LOG.info("Retrying failed call. Failure #%d, got: %s", status.getCause(), failedCount, status);

    if (call != null) {
      call.cancel();
      call = null;
    }

    retryExecutorService.schedule(this, nextBackOff, TimeUnit.MILLISECONDS);
  }

  private long getNextBackoff() {
    if (this.currentBackoff == null) {
      this.currentBackoff = retryOptions.createBackoff();
    }
    try {
      return currentBackoff.nextBackOffMillis();
    } catch (IOException e) {
    }
    return BackOff.STOP;
  }

  public SettableFuture<ResultT> getCompletionFuture() {
    return completionFuture;
  }

  /**
   * Calls
   * {@link BigtableAsyncRpc#call(Channel, Object, io.grpc.ClientCall.Listener, CancellationToken)}
   * with this as the listener so that retries happen correctly.
   */
  @Override
  public void run() {
    this.call = rpc.call(request, this);
  }

  public void cancel() {
    if (this.call != null) {
      call.cancel();
    }
  }
}
