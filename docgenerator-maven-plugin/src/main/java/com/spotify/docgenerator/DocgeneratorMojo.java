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

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import edu.emory.mathcs.backport.java.util.Collections;

import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.doxia.siterenderer.Renderer;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.reporting.AbstractMavenReport;
import org.apache.maven.reporting.MavenReportException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 */
@Mojo(name = "generate")
public class DocgeneratorMojo extends AbstractMavenReport {
  /**
   * Doxia Site Renderer.
   */
  @Component
  private Renderer siteRenderer;

  /**
   * Maven Project
   */
  @Component(role = MavenProject.class)
  private MavenProject project;

  /**
   * Paths to JSONClasses.
   */
  @Parameter(property = "jsonClassesFiles")
  private List<String> jsonClassesFiles;

  /**
   * Paths to RESTEndpoints.
   */
  @Parameter(property = "restEndpointsFiles")
  private List<String> restEndpointsFiles;

  /**
   * Jarfile paths for enums and the like.
   */
  @Parameter(property = "jarFiles")
  private List<String> jarFiles;

  /**
   * Location of the file.
   */
  @Parameter(defaultValue = "${project.build.directory}", property = "outputDir", required = true)
  private File outputDirectory;

  private URLClassLoader pluginClassLoader;

  private final Log log;

  private static final Map<String, String> PLAIN_TYPE_MAP = ImmutableMap.<String, String>builder()
      .put("java.lang.String", "string")
      .put("java.lang.Integer", "integer")
      .put("long", "integer")
      .put("int", "integer")
      .put("double", "double")
      .put("boolean", "boolean")
      .put("java.util.Date", "date")
      .build();
  private static final Set<String> SKIP_TYPES = ImmutableSet.<String>builder()
      .addAll(PLAIN_TYPE_MAP.keySet())
      .add("java.util.Map")
      .add("java.util.List")
      .build();

  public DocgeneratorMojo() {
    super();
    log = getLog();
  }

  @Override
  public String getDescription(Locale arg0) {
    return "Lists REST API endpoints and describes the datatypes used";
  }

  @Override
  public String getName(Locale arg0) {
    return "REST Endpoints And Transfer Classes";
  }

  @Override
  public String getOutputName() {
    return "rest";
  }

  @Override
  protected void executeReport(Locale arg0) throws MavenReportException {
    if (canGenerateReport()) {
      final Sink sink = getSink();
      log.debug("starting report....................");
      final ObjectMapper mapper = new ObjectMapper();

      documentRestEndpoints(sink, mapper);
      documentTransferClasses(sink, mapper);

      log.debug("Closing up report....................");

      sink.flush();
      sink.close();
    }
  }

  private void documentTransferClasses(final Sink sink, final ObjectMapper mapper)
      throws MavenReportException {

    final Set<String> knownClasses = Sets.newHashSet();
    final Set<String> referencedClasses = Sets.newHashSet();

    heading1(sink, "Transfer Classes");

    final Map<String, TransferClass> allClasses = loadClasses(mapper);
    knownClasses.addAll(allClasses.keySet());
    for (TransferClass transferClass : allClasses.values()) {
      if (transferClass.getMembers() != null) {
        for (TransferMember member : transferClass.getMembers()) {
          spiderKnownTypes(member.getType(), referencedClasses);
        }
      }
    }
    tableOfContentsHeader(sink);
    sink.list();
    final Set<String> everyClasses = Sets.newHashSet(knownClasses);
    everyClasses.addAll(referencedClasses);
    final List<String> everyClassesList = Lists.newArrayList(everyClasses);
    Collections.sort(everyClassesList);
    for (final String className : everyClassesList) {
      if (SKIP_TYPES.contains(className)) {
        continue;
      }
      sink.listItem();
      sink.link("#" + typeAnchor(className));
      sink.text(className);
      sink.link_();
      sink.listItem_();
    }
    sink.list_();

    for (final String className : everyClassesList) {
      // Don't document things like boolean, etc.
      if (SKIP_TYPES.contains(className)) {
        continue;
      }
      final TransferClass transferClass = allClasses.get(className);
      if (transferClass != null) {

        classHeading(sink, className);

        outputJavadoc(sink, transferClass.getJavadoc());

        if (transferClass.getMembers() != null) {
          sink.paragraph();
          sink.monospaced();
          sink.text(className + " {");
          sink.lineBreak();

          for (TransferMember member : transferClass.getMembers()) {
            sink.nonBreakingSpace();
            sink.nonBreakingSpace();
            sink.nonBreakingSpace();
            sink.nonBreakingSpace();
            sink.text("\"" + member.getName() + "\" : ");
            showType(sink, member.getType());
            sink.lineBreak();
          }
          sink.text("}");
          sink.monospaced_();
          sink.paragraph_();
        }

        sink.definitionList_();
      } else if (!knownClasses.contains(className)) {
        processEnum(sink, className);
      }
    }
  }

  private void spiderKnownTypes(TypeDescriptor type, Set<String> referencedClasses) {
    referencedClasses.add(type.getName());
    if (type.getTypeArguments() == null) {
      return;
    }
    for (final TypeDescriptor descriptor : type.getTypeArguments()) {
      spiderKnownTypes(descriptor, referencedClasses);
    }
  }

  private Map<String, TransferClass> loadClasses(final ObjectMapper mapper)
      throws MavenReportException {
    final Map<String, TransferClass> allClasses = Maps.newHashMap();

    for (final String path : jsonClassesFiles) {
      log.debug("looking at class file: " + path);
      try (FileInputStream ist = new FileInputStream(path)) {
        final Map<String, TransferClass> v = readTransferClasses(mapper, ist);
        allClasses.putAll(v);
      } catch (IOException e) {
        throw new MavenReportException("failed opening input file " + path, e);
      }
    }
    return allClasses;
  }

  private void restHeading(final Sink sink, String method, String path) {
    heading3WithAnchor(sink, endpointAnchor(method, path), method.toUpperCase() + " " + path);
  }

  private void documentRestEndpoints(final Sink sink, final ObjectMapper mapper)
      throws MavenReportException {
    heading1(sink, "REST Endpoints");

    final List<ResourceMethod> allMethods = Lists.newArrayList();
    for (final String path : restEndpointsFiles) {
      log.debug("looking at endpoint description file: " + path);
      try (FileInputStream ist = new FileInputStream(path)) {
        final List<ResourceMethod> resources = readResourceMethods(mapper, ist);
        allMethods.addAll(resources);

      } catch (IOException e) {
        throw new MavenReportException("failed opening input file " + path, e);
      }
    }

    Collections.sort(allMethods, new Comparator<ResourceMethod>() {
      @Override
      public int compare(ResourceMethod o1, ResourceMethod o2) {
        final int cmp = o1.getPath().compareTo(o2.getPath());
        if (cmp != 0) {
          return cmp;
        }

        return o1.getMethod().compareTo(o2.getMethod());
      }
    });

    tableOfContentsHeader(sink);
    sink.list();
    for (final ResourceMethod method : allMethods) {
      sink.listItem();
      sink.link("#" + endpointAnchor(method.getMethod(), method.getPath()));
      sink.text(method.getMethod().toUpperCase() + " " + method.getPath());
      sink.link_();
      sink.listItem_();
    }
    sink.list_();

    for (final ResourceMethod method : allMethods) {
      handleRestEndpoint(sink, method);
    }
  }

  private void handleRestEndpoint(Sink sink, ResourceMethod method) {
    restHeading(sink, method.getMethod(), method.getPath());
    outputJavadoc(sink, method.getJavadoc());
    List<ResourceArgument> args = method.getArguments();
    if (args != null && !args.isEmpty()) {
      heading4(sink, "Arguments");

      sink.list();
      for (ResourceArgument arg : args) {
        sink.listItem();
        boldText(sink, arg.getName());
        sink.text(" ");
        showType(sink, arg.getType());
        sink.listItem_();
      }
      sink.list_();
    }

    heading4(sink, "Returns");

    sink.list();
    if (method.getReturnContentType() != null) {
      sink.listItem();
      boldText(sink, "Content-Type:");
      sink.definition();
      sink.text(method.getReturnContentType());
      sink.listItem_();
    }

    sink.listItem();
    boldText(sink, "Object-Type:");
    sink.definition();
    showType(sink, method.getReturnType());
    sink.listItem_();

    sink.list_();

  }

  private void boldText(Sink sink, final String term) {
    sink.bold();
    sink.text(term);
    sink.bold_();
  }

  private void classHeading(final Sink sink, String className) {
    heading3WithAnchor(sink, typeAnchor(className), "Type: " + className);
  }

  private void processEnum(Sink sink, String className) {
    Class<?> clazz = getClassForNameIsh(sink, className);
    if (clazz == null) {
      sink.text("Was not able to find class: " + className);
      sink.lineBreak();
      return;
    }
    if (clazz.isEnum()) {
      classHeading(sink, className);
      final Object[] constants = clazz.getEnumConstants();
      final List<String> constantsWrapped = Lists.newArrayList();
      for (Object c : constants) {
        constantsWrapped.add("\"" + c + "\"");
      }
      sink.text("Enumerated Type.  Valid values are: ");
      sink.monospaced();
      sink.text(Joiner.on(", ").join(constantsWrapped));
      sink.monospaced_();
      sink.lineBreak();
    } else {
      sink.text("!??!?!!?" + clazz);
      sink.lineBreak();
    }

  }

  private Class<?> getClassForNameIsh(Sink sink, String className) {
    try {
      return getClassForName(className);
    } catch (ClassNotFoundException e) {
      final List<String> bits = Splitter.on(".").splitToList(className);
      final String childClassName = Joiner.on('.').join(bits.subList(0, bits.size() - 1));
      try {
        Class<?> clazz = getClassForName(childClassName);
        for (Class<?> clazzy : clazz.getDeclaredClasses()) {
          if (className.equals(clazzy.getCanonicalName())) {
            return clazzy;
          }
        }
        return null;
      } catch (ClassNotFoundException e1) {
        sink.text(" -- can't find class");
        sink.lineBreak();
        return null;
      } catch (MalformedURLException e1) {
        sink.text(" -- inner url is hosed");
        sink.lineBreak();
        return null;
      }
    } catch (MalformedURLException e) {
      sink.text(" -- url is hosed");
      sink.lineBreak();
      return null;
    }
  }

  private void showType(Sink sink, TypeDescriptor type) {
    if (PLAIN_TYPE_MAP.containsKey(type.getName())) {
      sink.text(PLAIN_TYPE_MAP.get(type.getName()));
      return;
    }

    if (type.getTypeArguments() == null || type.getTypeArguments().isEmpty()) {
      typeLink(sink, type);
      return;
    }

    if ("java.util.Map".equals(type.getName())) {
      sink.text("{");
      showType(sink, type.getTypeArguments().get(0));
      sink.text(" : ");
      showType(sink, type.getTypeArguments().get(1));
      sink.text(", }");
      return;
    }

    if ("java.util.List".equals(type.getName())) {
      sink.text("[");
      showType(sink, type.getTypeArguments().get(0));
      sink.text(", ]");
      return;
    }

    if ("com.google.common.base.Optional".equals(type.getName())) {
      showType(sink, type.getTypeArguments().get(0));
      return;
    }
    sink.text("<??" + type.getName() + "??>");
  }

  private void typeLink(Sink sink, TypeDescriptor type) {
    sink.link("#" + typeAnchor(type.getName()));
    sink.text(type.getName());
    sink.link_();
  }

  private List<ResourceMethod> readResourceMethods(ObjectMapper mapper, FileInputStream ist)
      throws JsonProcessingException, IOException {
    final ObjectReader reader = mapper.reader(new TypeReference<List<ResourceMethod>>(){});
    return reader.readValue(ist);
  }

  private Map<String, TransferClass> readTransferClasses(final ObjectMapper mapper,
      FileInputStream ist) throws IOException, JsonProcessingException {
    final ObjectReader reader = mapper.reader(new TypeReference<Map<String, TransferClass>>(){});
    return reader.readValue(ist);
  }

  private String endpointAnchor(String method, String path) {
    return method + "-" + path.replace("/", "-").replace("{", "-")
        .replace("}", "-");
  }

  private String typeAnchor(String name) {
    return name.replace(".", "-");
  }

  private void tableOfContentsHeader(final Sink sink) {
    heading2(sink, "Table Of Contents");
  }

  private void outputJavadoc(Sink sink, final String javadoc) {
    sink.paragraph();
    processJavadoc(sink, javadoc);
    sink.paragraph_();
  }

  private void processJavadoc(Sink sink, final String javadoc) {

    if (javadoc == null) {
      return;
    }

    // TODO(drewc) split javadoc on lines and put in <p></p> bits for blank (or whitespace only)
    // lines.
    sink.rawText(javadoc);
  }

  private void heading1(final Sink sink, final String heading) {
    sink.section1();
    sink.sectionTitle1();
    sink.text(heading);
    sink.sectionTitle1_();
    sink.section1_();
  }

  private void heading2(final Sink sink, final String string) {
    sink.section2();
    sink.sectionTitle2();
    sink.text(string);
    sink.sectionTitle2_();
    sink.section2_();
  }

  private void heading3WithAnchor(final Sink sink, final String anchor, final String text) {
    sink.section3();
    sink.sectionTitle3();
    sink.anchor(anchor); sink.anchor_();
    sink.text(text);
    sink.sectionTitle3_();
    sink.section3_();
  }

  private void heading4(Sink sink, final String string) {
    sink.section4();
    sink.sectionTitle4();
    sink.text(string);
    sink.sectionTitle4_();
    sink.section4_();
  }

  @Override
  protected String getOutputDirectory() {
    return outputDirectory.getAbsolutePath();
  }

  @Override
  protected MavenProject getProject() {
    return project;
  }

  @Override
  protected Renderer getSiteRenderer() {
    return siteRenderer;
  }

  private Class<?> getClassForName(final String name)
      throws ClassNotFoundException, MalformedURLException {
    final URLClassLoader loader = getPluginClassLoader();
    return loader.loadClass(name);
  }

  private URLClassLoader getPluginClassLoader() throws MalformedURLException {
    if (pluginClassLoader != null) {
      return pluginClassLoader;
    }

    final List<URL> jarUrls = Lists.newArrayList();
    for (final String jarName : jarFiles) {
      jarUrls.add(new URL("file://" + jarName));
    }
    final URLClassLoader loader = new URLClassLoader(jarUrls.toArray(new URL[jarUrls.size()]));
    pluginClassLoader = loader;
    return loader;
  }

}
