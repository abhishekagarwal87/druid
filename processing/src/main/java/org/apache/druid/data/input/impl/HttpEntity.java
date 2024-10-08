/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.data.input.impl;

import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.net.HttpHeaders;
import org.apache.druid.data.input.RetryingInputEntity;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.metadata.PasswordProvider;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.util.Base64;
import java.util.Map;

public class HttpEntity extends RetryingInputEntity
{
  private static final Logger LOG = new Logger(HttpEntity.class);

  private final URI uri;
  @Nullable
  private final String httpAuthenticationUsername;
  @Nullable
  private final PasswordProvider httpAuthenticationPasswordProvider;

  private final Map<String, String> requestHeaders;

  HttpEntity(
      URI uri,
      @Nullable String httpAuthenticationUsername,
      @Nullable PasswordProvider httpAuthenticationPasswordProvider,
      @Nullable Map<String, String> requestHeaders
  )
  {
    this.uri = uri;
    this.httpAuthenticationUsername = httpAuthenticationUsername;
    this.httpAuthenticationPasswordProvider = httpAuthenticationPasswordProvider;
    this.requestHeaders = requestHeaders;
  }

  @Override
  public URI getUri()
  {
    return uri;
  }

  @Override
  protected InputStream readFrom(long offset) throws IOException
  {
    return openInputStream(uri, httpAuthenticationUsername, httpAuthenticationPasswordProvider, offset, requestHeaders);
  }

  @Override
  protected String getPath()
  {
    return uri.getPath();
  }

  @Override
  public Predicate<Throwable> getRetryCondition()
  {
    return t -> t instanceof IOException;
  }

  public static InputStream openInputStream(URI object, String userName, PasswordProvider passwordProvider, long offset, final Map<String, String> requestHeaders)
      throws IOException
  {
    final URLConnection urlConnection = object.toURL().openConnection();
    if (requestHeaders != null && requestHeaders.size() > 0) {
      for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
        urlConnection.addRequestProperty(entry.getKey(), entry.getValue());
      }
    }
    if (!Strings.isNullOrEmpty(userName) && passwordProvider != null) {
      String userPass = userName + ":" + passwordProvider.getPassword();
      String basicAuthString = "Basic " + Base64.getEncoder().encodeToString(StringUtils.toUtf8(userPass));
      urlConnection.setRequestProperty("Authorization", basicAuthString);
    }
    // Set header for range request.
    // Since we need to set only the start offset, the header is "bytes=<range-start>-".
    // See https://tools.ietf.org/html/rfc7233#section-2.1
    urlConnection.addRequestProperty(HttpHeaders.RANGE, StringUtils.format("bytes=%d-", offset));
    final String contentRange = urlConnection.getHeaderField(HttpHeaders.CONTENT_RANGE);
    final boolean withContentRange = contentRange != null && contentRange.startsWith("bytes ");
    if (withContentRange && offset > 0) {
      return urlConnection.getInputStream();
    } else {
      if (!withContentRange && offset > 0) {
        LOG.warn(
            "Since the input source doesn't support range requests, the object input stream is opened from the start and "
            + "then skipped. This may make the ingestion speed slower. Consider enabling prefetch if you see this message"
            + " a lot."
        );
      }
      InputStream in = urlConnection.getInputStream();
      try {
        final long skipped = in.skip(offset);
        if (skipped != offset) {
          in.close();
          throw new ISE("Requested to skip [%s] bytes, but actual number of bytes skipped is [%s]", offset, skipped);
        } else {
          return in;
        }
      }
      catch (IOException ex) {
        in.close();
        throw ex;
      }
    }
  }
}
