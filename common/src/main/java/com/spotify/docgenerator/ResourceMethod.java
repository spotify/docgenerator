/*
 * Copyright (c) 2014 Spotify AB.
 *
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

package com.spotify.docgenerator;

import com.google.common.base.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class ResourceMethod {
  private final String name;
  private final String method;
  private final String path;
  private final String returnContentType;
  private final TypeDescriptor returnType;
  private final List<ResourceArgument> arguments;
  private final String javadoc;

  public ResourceMethod(@JsonProperty("name") String name,
                         @JsonProperty("method") String method,
                         @JsonProperty("path") String path,
                         @JsonProperty("returnContentType") String returnContentType,
                         @JsonProperty("returnType") TypeDescriptor returnType,
                         @JsonProperty("resourceArgument") List<ResourceArgument> arguments,
                         @JsonProperty("javadoc") String javadoc) {
    this.name = name;
    this.method = method;
    this.path = path;
    this.returnContentType = returnContentType;
    this.returnType = returnType;
    this.arguments = arguments;
    this.javadoc = javadoc;
  }

  public String getName() {
    return name;
  }

  public String getMethod() {
    return method;
  }

  public String getPath() {
    return path;
  }

  public String getReturnContentType() {
    return returnContentType;
  }

  public TypeDescriptor getReturnType() {
    return returnType;
  }

  public List<ResourceArgument> getArguments() {
    return arguments;
  }

  public String getJavadoc() {
    return javadoc;
  }

  @Override
  public String toString() {
    return Objects.toStringHelper("ResourceMethod")
        .add("name", name)
        .add("method", method)
        .add("path", path)
        .add("returnContentType", returnContentType)
        .add("returnType", returnType)
        .add("arguments", arguments)
        .add("javadoc", javadoc)
        .toString();
  }
}
