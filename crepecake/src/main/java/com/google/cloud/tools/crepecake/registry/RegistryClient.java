/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.crepecake.registry;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpStatusCodes;
import com.google.cloud.tools.crepecake.blob.Blob;
import com.google.cloud.tools.crepecake.blob.BlobDescriptor;
import com.google.cloud.tools.crepecake.http.Authorization;
import com.google.cloud.tools.crepecake.http.Connection;
import com.google.cloud.tools.crepecake.http.Request;
import com.google.cloud.tools.crepecake.http.Response;
import com.google.cloud.tools.crepecake.image.DescriptorDigest;
import com.google.cloud.tools.crepecake.image.json.ManifestTemplate;
import com.google.cloud.tools.crepecake.image.json.V21ManifestTemplate;
import com.google.cloud.tools.crepecake.image.json.V22ManifestTemplate;
import com.google.cloud.tools.crepecake.json.JsonTemplateMapper;
import com.google.cloud.tools.crepecake.registry.json.ErrorEntryTemplate;
import com.google.cloud.tools.crepecake.registry.json.ErrorResponseTemplate;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import javax.annotation.Nullable;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.apache.http.NoHttpResponseException;

/** Interfaces with a registry. */
public class RegistryClient {

  private static final String PROTOCOL = "https";

  @Nullable private final Authorization authorization;
  private final RegistryEndpointProperties registryEndpointProperties;

  public RegistryClient(@Nullable Authorization authorization, String serverUrl, String imageName) {
    this.authorization = authorization;
    this.registryEndpointProperties = new RegistryEndpointProperties(serverUrl, imageName);
  }

  /** Gets the {@link RegistryAuthenticator} to authenticate pulls from the registry. */
  public RegistryAuthenticator getRegistryAuthenticator() throws IOException, RegistryException {
    // Gets the WWW-Authenticate header (eg. 'WWW-Authenticate: Bearer realm="https://gcr.io/v2/token",service="gcr.io"')
    AuthenticationMethodRetriever authenticationMethodRetriever =
        new AuthenticationMethodRetriever(registryEndpointProperties);
    return callRegistryEndpoint(authenticationMethodRetriever);
  }

  /**
   * Pulls the image manifest for a specific tag.
   *
   * @param imageTag the tag to pull on
   * @param manifestTemplateClass the specific version of manifest template to pull, or {@link
   *     ManifestTemplate} to pull either {@link V22ManifestTemplate} or {@link V21ManifestTemplate}
   */
  public <T extends ManifestTemplate> T pullManifest(
      String imageTag, Class<T> manifestTemplateClass) throws IOException, RegistryException {
    ManifestPuller<T> manifestPuller =
        new ManifestPuller<>(registryEndpointProperties, imageTag, manifestTemplateClass);
    return callRegistryEndpoint(manifestPuller);
  }

  public ManifestTemplate pullManifest(String imageTag) throws IOException, RegistryException {
    return pullManifest(imageTag, ManifestTemplate.class);
  }

  /** Pushes the image manifest for a specific tag. */
  public void pushManifest(V22ManifestTemplate manifestTemplate, String imageTag)
      throws IOException, RegistryException {
    ManifestPusher manifestPusher =
        new ManifestPusher(registryEndpointProperties, manifestTemplate, imageTag);
    callRegistryEndpoint(manifestPusher);
  }

  /**
   * @return the BLOB's {@link BlobDescriptor} if the BLOB exists on the registry, or {@code null}
   *     if it doesn't
   */
  public BlobDescriptor checkBlob(DescriptorDigest blobDigest)
      throws IOException, RegistryException {
    BlobChecker blobChecker = new BlobChecker(registryEndpointProperties, blobDigest);
    return callRegistryEndpoint(blobChecker);
  }

  /**
   * Downloads the BLOB to a file.
   *
   * @param blobDigest the digest of the BLOB to download
   * @param destPath the path of the file to write to
   * @return a {@link Blob} backed by the file at {@code destPath}. The file at {@code destPath}
   *     must exist for {@link Blob} to be valid.
   */
  public Blob pullBlob(DescriptorDigest blobDigest, Path destPath)
      throws RegistryException, IOException {
    BlobPuller blobPuller = new BlobPuller(registryEndpointProperties, blobDigest, destPath);
    return callRegistryEndpoint(blobPuller);
  }

  // TODO: Add mount with 'from' parameter
  /**
   * Pushes the BLOB, or skips if the BLOB already exists on the registry.
   *
   * @param blobDigest the digest of the BLOB, used for existence-check
   * @param blob the BLOB to push
   * @return {@code true} if the BLOB already exists on the registry and pushing was skipped; false
   *     if the BLOB was pushed
   */
  public boolean pushBlob(DescriptorDigest blobDigest, Blob blob)
      throws IOException, RegistryException {
    BlobPusher blobPusher = new BlobPusher(registryEndpointProperties, blobDigest, blob);

    // POST /v2/<name>/blobs/uploads/?mount={blob.digest}
    String locationHeader = callRegistryEndpoint(blobPusher.initializer());
    if (locationHeader == null) {
      // The BLOB exists already.
      return true;
    }
    URL patchLocation = new URL(locationHeader);

    // PATCH <Location> with BLOB
    URL putLocation = new URL(callRegistryEndpoint(blobPusher.writer(patchLocation)));

    // PUT <Location>?digest={blob.digest}
    callRegistryEndpoint(blobPusher.committer(putLocation));

    return false;
  }

  private String getApiRouteBase() {
    return PROTOCOL + "://" + registryEndpointProperties.getServerUrl() + "/v2/";
  }

  /**
   * Calls the registry endpoint.
   *
   * @param registryEndpointProvider the {@link RegistryEndpointProvider} to the endpoint
   */
  private <T> T callRegistryEndpoint(RegistryEndpointProvider<T> registryEndpointProvider)
      throws IOException, RegistryException {
    return callRegistryEndpoint(null, registryEndpointProvider);
  }

  /**
   * Calls the registry endpoint with an override URL.
   *
   * @param url the endpoint URL to call, or {@code null} to use default from {@code
   *     registryEndpointProvider}
   * @param registryEndpointProvider the {@link RegistryEndpointProvider} to the endpoint
   */
  private <T> T callRegistryEndpoint(
      @Nullable URL url, RegistryEndpointProvider<T> registryEndpointProvider)
      throws IOException, RegistryException {
    if (url == null) {
      url = registryEndpointProvider.getApiRoute(getApiRouteBase());
    }

    try (Connection connection = new Connection(url)) {
      Request request =
          Request.builder()
              .setAuthorization(authorization)
              .setAccept(registryEndpointProvider.getAccept())
              .setBody(registryEndpointProvider.getContent())
              .build();
      Response response = connection.send(registryEndpointProvider.getHttpMethod(), request);

      return registryEndpointProvider.handleResponse(response);

    } catch (HttpResponseException ex) {
      // First, see if the endpoint provider handles an exception as an expected response.
      try {
        return registryEndpointProvider.handleHttpResponseException(ex);

      } catch (HttpResponseException httpResponseException) {
        if (httpResponseException.getStatusCode() == HttpStatusCodes.STATUS_CODE_BAD_REQUEST
            || httpResponseException.getStatusCode() == HttpStatusCodes.STATUS_CODE_NOT_FOUND
            || httpResponseException.getStatusCode()
                == HttpStatusCodes.STATUS_CODE_METHOD_NOT_ALLOWED) {
          // The name or reference was invalid.
          ErrorResponseTemplate errorResponse =
              JsonTemplateMapper.readJson(
                  httpResponseException.getContent(), ErrorResponseTemplate.class);
          RegistryErrorExceptionBuilder registryErrorExceptionBuilder =
              new RegistryErrorExceptionBuilder(
                  registryEndpointProvider.getActionDescription(), httpResponseException);
          for (ErrorEntryTemplate errorEntry : errorResponse.getErrors()) {
            registryErrorExceptionBuilder.addReason(errorEntry);
          }

          throw registryErrorExceptionBuilder.build();

        } else if (httpResponseException.getStatusCode() == HttpStatusCodes.STATUS_CODE_UNAUTHORIZED
            || httpResponseException.getStatusCode() == HttpStatusCodes.STATUS_CODE_FORBIDDEN) {
          throw new RegistryUnauthorizedException(httpResponseException);

        } else if (httpResponseException.getStatusCode()
            == HttpStatusCodes.STATUS_CODE_TEMPORARY_REDIRECT) {
          return callRegistryEndpoint(
              new URL(httpResponseException.getHeaders().getLocation()), registryEndpointProvider);

        } else {
          // Unknown
          throw httpResponseException;
        }
      }

    } catch (NoHttpResponseException ex) {
      throw new RegistryNoResponseException(ex);

    } catch (SSLPeerUnverifiedException ex) {
      // Fall-back to HTTP
      GenericUrl httpUrl = new GenericUrl(url);
      httpUrl.setScheme("http");
      return callRegistryEndpoint(httpUrl.toURL(), registryEndpointProvider);
    }
  }
}