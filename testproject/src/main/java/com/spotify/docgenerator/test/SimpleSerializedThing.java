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

package com.spotify.docgenerator.test;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Some javadoc for you to marvel at.
 *
 * @author drewc
 */
public class SimpleSerializedThing {
  private final String name;
  private final List<String> values;

  public SimpleSerializedThing(@JsonProperty("name") String name,
                               @JsonProperty("value") List<String> values) {
    this.name = name;
    this.values = values;
  }

  public String getName() {
    return name;
  }

  public List<String> getValue() {
    return values;
  }
}
