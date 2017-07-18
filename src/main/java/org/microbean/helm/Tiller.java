/* -*- mode: Java; c-basic-offset: 2; indent-tabs-mode: nil; coding: utf-8-unix -*-
 *
 * Copyright © 2017 MicroBean.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.microbean.helm;

import java.io.Closeable;
import java.io.IOException;

import java.net.MalformedURLException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import hapi.services.tiller.ReleaseServiceGrpc;
import hapi.services.tiller.ReleaseServiceGrpc.ReleaseServiceBlockingStub;
import hapi.services.tiller.ReleaseServiceGrpc.ReleaseServiceFutureStub;
import hapi.services.tiller.ReleaseServiceGrpc.ReleaseServiceStub;

import io.fabric8.kubernetes.client.DefaultKubernetesClient; // for javadoc only
import io.fabric8.kubernetes.client.HttpClientAware;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;

import io.grpc.stub.MetadataUtils;

import org.microbean.kubernetes.Pods;

/**
 * A convenience class for communicating with a <a
 * href="https://docs.helm.sh/glossary/#tiller"
 * target="_parent">Tiller server</a>.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see ReleaseServiceGrpc
 */
public class Tiller implements Closeable {


  /*
   * Static fields.
   */


  /**
   * The version of Tiller {@link Tiller} instances expect.
   *
   * <p>This field is never {@code null}.</p>
   */
  public static final String VERSION = "2.5.0";

  /**
   * The Kubernetes namespace into which Tiller server instances are
   * most commonly installed.
   *
   * <p>This field is never {@code null}.</p>
   */
  public static final String DEFAULT_NAMESPACE = "kube-system";

  /**
   * The port on which Tiller server instances most commonly listen.
   */
  public static final int DEFAULT_PORT = 44134;

  /**
   * The Kubernetes labels with which most Tiller instances are
   * annotated.
   *
   * <p>This field is never {@code null}.</p>
   */
  public static final Map<String, String> DEFAULT_LABELS;
  
  /**
   * A {@link Metadata} that ensures that certain Tiller-related
   * headers are passed with every gRPC call.
   *
   * <p>This field is never {@code null}.</p>
   */
  private static final Metadata metadata = new Metadata();


  /*
   * Static initializer.
   */
  

  /**
   * Static initializer; initializes the {@link #DEFAULT_LABELS}
   * {@code static} field (among others).
   */
  static {
    final Map<String, String> labels = new HashMap<>();
    labels.put("name", "tiller");
    labels.put("app", "helm");
    DEFAULT_LABELS = Collections.unmodifiableMap(labels);
    metadata.put(Metadata.Key.of("x-helm-api-client", Metadata.ASCII_STRING_MARSHALLER), VERSION);
  }


  /*
   * Instance fields.
   */
  

  /**
   * The {@link LocalPortForward} being used to communicate (most
   * commonly) with a Kubernetes pod housing a Tiller server.
   *
   * <p>This field may be {@code null}.</p>
   *
   * @see #Tiller(LocalPortForward)
   */
  private final LocalPortForward portForward;

  /**
   * The {@link ManagedChannel} over which communications with a
   * Tiller server will be conducted.
   *
   * <p>This field is never {@code null}.</p>
   */
  private final ManagedChannel channel;


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link Tiller} that will use the supplied {@link
   * ManagedChannel} for communication.
   *
   * @param channel the {@link ManagedChannel} over which
   * communications will be conducted; must not be {@code null}
   *
   * @exception NullPointerException if {@code channel} is {@code
   * null}
   */
  public Tiller(final ManagedChannel channel) {
    super();
    Objects.requireNonNull(channel);
    this.portForward = null;
    this.channel = channel;
  }

  /**
   * Creates a new {@link Tiller} that will use information from the
   * supplied {@link LocalPortForward} to establish a communications
   * channel with the Tiller server.
   *
   * @param portForward the {@link LocalPortForward} to use; must not
   * be {@code null}
   *
   * @exception NullPointerException if {@code portForward} is {@code
   * null}
   */
  public Tiller(final LocalPortForward portForward) {
    super();
    Objects.requireNonNull(portForward);
    this.portForward = null; // yes, null
    this.channel = this.buildChannel(portForward);
  }

  /**
   * Creates a new {@link Tiller} that will forward a local port to
   * port {@code 44134} on a Pod housing Tiller in the {@code
   * kube-system} namespace running in the Kubernetes cluster with
   * which the supplied {@link KubernetesClient} is capable of
   * communicating.
   *
   * <p>The {@linkplain Pods#getFirstReadyPod(Listable) first ready
   * Pod} with a {@code name} label whose value is {@code tiller} and
   * with an {@code app} label whose value is {@code helm} is deemed
   * to be the pod housing the Tiller instance to connect to.  (This
   * duplicates the default logic of the {@code helm} command line
   * executable.)</p>
   *
   * @param <T> a {@link KubernetesClient} implementation that is also
   * an {@link HttpClientAware} implementation, such as {@link
   * DefaultKubernetesClient}
   *
   * @param client the {@link KubernetesClient}-and-{@link
   * HttpClientAware} implementation that can communicate with a
   * Kubernetes cluster; must not be {@code null}
   *
   * @exception MalformedURLException if there was a problem
   * identifying a Pod within the cluster that houses a Tiller instance
   *
   * @exception NullPointerException if {@code client} is {@code null}
   */
  public <T extends HttpClientAware & KubernetesClient> Tiller(final T client) throws MalformedURLException {
    this(client, DEFAULT_NAMESPACE, DEFAULT_PORT, DEFAULT_LABELS);
  }

  /**
   * Creates a new {@link Tiller} that will forward a local port to
   * port {@code 44134} on a Pod housing Tiller in the supplied
   * namespace running in the Kubernetes cluster with which the
   * supplied {@link KubernetesClient} is capable of communicating.
   *
   * <p>The {@linkplain Pods#getFirstReadyPod(Listable) first ready
   * Pod} with a {@code name} label whose value is {@code tiller} and
   * with an {@code app} label whose value is {@code helm} is deemed
   * to be the pod housing the Tiller instance to connect to.  (This
   * duplicates the default logic of the {@code helm} command line
   * executable.)</p>
   *
   * @param <T> a {@link KubernetesClient} implementation that is also
   * an {@link HttpClientAware} implementation, such as {@link
   * DefaultKubernetesClient}
   *
   * @param client the {@link KubernetesClient}-and-{@link
   * HttpClientAware} implementation that can communicate with a
   * Kubernetes cluster; must not be {@code null}
   *
   * @param namespaceHousingTiller the namespace within which a Tiller
   * instance is hopefully running; if {@code null}, then the value of
   * {@link #DEFAULT_NAMESPACE} will be used instead
   *
   * @exception MalformedURLException if there was a problem
   * identifying a Pod within the cluster that houses a Tiller instance
   *
   * @exception NullPointerException if {@code client} is {@code null}
   */
  public <T extends HttpClientAware & KubernetesClient> Tiller(final T client, final String namespaceHousingTiller) throws MalformedURLException {
    this(client, namespaceHousingTiller, DEFAULT_PORT, DEFAULT_LABELS);
  }

  /**
   * Creates a new {@link Tiller} that will forward a local port to
   * the supplied (remote) port on a Pod housing Tiller in the supplied
   * namespace running in the Kubernetes cluster with which the
   * supplied {@link KubernetesClient} is capable of communicating.
   *
   * <p>The {@linkplain Pods#getFirstReadyPod(Listable) first ready
   * Pod} with labels matching the supplied {@code tillerLabels} is
   * deemed to be the pod housing the Tiller instance to connect
   * to.</p>
   *
   * @param <T> a {@link KubernetesClient} implementation that is also
   * an {@link HttpClientAware} implementation, such as {@link
   * DefaultKubernetesClient}
   *
   * @param client the {@link KubernetesClient}-and-{@link
   * HttpClientAware} implementation that can communicate with a
   * Kubernetes cluster; must not be {@code null}
   *
   * @param namespaceHousingTiller the namespace within which a Tiller
   * instance is hopefully running; if {@code null}, then the value of
   * {@link #DEFAULT_NAMESPACE} will be used instead
   *
   * @param tillerPort the remote port to attempt to forward a local
   * port to; normally {@code 44134}
   *
   * @param tillerLabels a {@link Map} representing the Kubernetes
   * labels (and their values) identifying a Pod housing a Tiller
   * instance; if {@code null} then the value of {@link
   * #DEFAULT_LABELS} will be used instead
   *
   * @exception MalformedURLException if there was a problem
   * identifying a Pod within the cluster that houses a Tiller instance
   *
   * @exception NullPointerException if {@code client} is {@code null}
   */
  public <T extends HttpClientAware & KubernetesClient> Tiller(final T client,
                                                               String namespaceHousingTiller,
                                                               int tillerPort,
                                                               Map<String, String> tillerLabels) throws MalformedURLException {
    super();
    Objects.requireNonNull(client);
    if (namespaceHousingTiller == null || namespaceHousingTiller.isEmpty()) {
      namespaceHousingTiller = DEFAULT_NAMESPACE;
    }
    if (tillerPort <= 0) {
      tillerPort = DEFAULT_PORT;
    }
    if (tillerLabels == null) {
      tillerLabels = DEFAULT_LABELS;
    }
    this.portForward = Pods.forwardPort(client.getHttpClient(), client.pods().inNamespace(namespaceHousingTiller).withLabels(tillerLabels), tillerPort);
    this.channel = this.buildChannel(this.portForward);
  }


  /*
   * Instance methods.
   */
  

  /**
   * Creates a {@link ManagedChannel} for communication with Tiller
   * from the information contained in the supplied {@link
   * LocalPortForward}.
   *
   * <p><strong>Note:</strong> This method is (deliberately) called
   * from constructors so must have stateless semantics.</p>
   *
   * <p>This method never returns {@code null}.</p>
   *
   * <p>Overrides of this method must not return {@code null}.</p>
   *
   * @param portForward a {@link LocalPortForward}; must not be {@code
   * null}
   *
   * @return a non-{@code null} {@link ManagedChannel}
   *
   * @exception NullPointerException if {@code portForward} is {@code
   * null}
   */
  protected ManagedChannel buildChannel(final LocalPortForward portForward) {
    return ManagedChannelBuilder.forAddress(portForward.getLocalAddress().getHostAddress(), portForward.getLocalPort()).usePlaintext(true).build();
  }

  /**
   * Closes this {@link Tiller} after use.
   *
   * @exception IOException if there was a problem closing the
   * underlying connection to a Tiller instance
   */
  @Override
  public void close() throws IOException {
    if (this.channel != null) {
      this.channel.shutdownNow();
    }
    if (this.portForward != null) {
      this.portForward.close();
    }
  }

  /**
   * Returns the gRPC-generated {@link ReleaseServiceBlockingStub}
   * object that represents the capabilities of the Tiller server.
   *
   * <p>This method will never return {@code null}.</p>
   *
   * <p>Overrides of this method must never return {@code null}.</p>
   *
   * @return a non-{@code null} {@link ReleaseServiceBlockingStub}
   *
   * @see ReleaseServiceBlockingStub
   */
  public ReleaseServiceBlockingStub getReleaseServiceBlockingStub() {
    ReleaseServiceBlockingStub returnValue = null;
    if (this.channel != null) {
      returnValue = MetadataUtils.attachHeaders(ReleaseServiceGrpc.newBlockingStub(this.channel), metadata);
    }
    return returnValue;
  }

  /**
   * Returns the gRPC-generated {@link ReleaseServiceFutureStub}
   * object that represents the capabilities of the Tiller server.
   *
   * <p>This method will never return {@code null}.</p>
   *
   * <p>Overrides of this method must never return {@code null}.</p>
   *
   * @return a non-{@code null} {@link ReleaseServiceFutureStub}
   *
   * @see ReleaseServiceFutureStub
   */
  public ReleaseServiceFutureStub getReleaseServiceFutureStub() {
    ReleaseServiceFutureStub returnValue = null;
    if (this.channel != null) {
      returnValue = MetadataUtils.attachHeaders(ReleaseServiceGrpc.newFutureStub(this.channel), metadata);
    }
    return returnValue;
  }

  /**
   * Returns the gRPC-generated {@link ReleaseServiceStub}
   * object that represents the capabilities of the Tiller server.
   *
   * <p>This method will never return {@code null}.</p>
   *
   * <p>Overrides of this method must never return {@code null}.</p>
   *
   * @return a non-{@code null} {@link ReleaseServiceStub}
   *
   * @see ReleaseServiceStub
   */  
  public ReleaseServiceStub getReleaseServiceStub() {
    ReleaseServiceStub returnValue = null;
    if (this.channel != null) {
      returnValue = MetadataUtils.attachHeaders(ReleaseServiceGrpc.newStub(this.channel), metadata);
    }
    return returnValue;
  }

}