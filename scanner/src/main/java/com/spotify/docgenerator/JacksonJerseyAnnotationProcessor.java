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

import com.google.auto.service.AutoService;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.spotify.docgenerator.ResourceArgument.Location;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic.Kind;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import javax.ws.rs.Consumes;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;

import static com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY;
import static com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;

@SupportedAnnotationTypes({
    "com.fasterxml.jackson.annotation.JsonProperty",
    "com.fasterxml.jackson.databind.annotation.JsonSerialize",
    "javax.ws.rs.GET",
    "javax.ws.rs.POST",
    "javax.ws.rs.PUT",
    "javax.ws.rs.DELETE",
    "com.spotify.helios.master.http.PATCH",
    "com.spotify.docgenerator.DocEnum"
    })
@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedOptions({ "debug", "verify" })
@AutoService(Processor.class)
public class JacksonJerseyAnnotationProcessor extends AbstractProcessor {
  private static final List<String> METHOD_ANNOTATIONS = Lists.newArrayList(
      "javax.ws.rs.GET",
      "javax.ws.rs.POST",
      "javax.ws.rs.PUT",
      "javax.ws.rs.DELETE",
      "com.spotify.helios.master.http.PATCH");

  private static final ObjectWriter NORMALIZING_OBJECT_WRITER = new ObjectMapper()
      .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
      .configure(SORT_PROPERTIES_ALPHABETICALLY, true)
      .configure(ORDER_MAP_ENTRIES_BY_KEYS, true)
      .configure(WRITE_DATES_AS_TIMESTAMPS, false)
      .writer();

  private final Map<String, TransferClass> jsonClasses = Maps.newHashMap();
  private final Map<String, ResourceClass> resourceClasses = Maps.newHashMap();

  private final List<String> debugMessages = Lists.newArrayList();

  @Override
  public boolean process(final Set<? extends TypeElement> annotations,
      final RoundEnvironment roundEnv) {
    if (roundEnv.processingOver()) {
      generateOutput();
    } else {
      processAnnotations(annotations, roundEnv);
    }
    return true;
  }

  private void processAnnotations(final Set<? extends TypeElement> annotations,
                                  final RoundEnvironment roundEnv) {
    processJacksonAnnotations(roundEnv);
    processRESTEndpointAnnotations(annotations, roundEnv);
  }

  private void processRESTEndpointAnnotations(final Set<? extends TypeElement> annotations,
                                              final RoundEnvironment roundEnv) {
    for (final String methodAnnotation : METHOD_ANNOTATIONS) {
      for (final TypeElement foundAnnotations : annotations) {
        if (foundAnnotations.toString().equals(methodAnnotation)) {
          processFoundRestAnnotations(foundAnnotations, roundEnv);
        }
      }
    }
  }

  /**
   * Go through found REST Annotations and produce {@link ResourceClass}es from what we find.
   */
  private void processFoundRestAnnotations(final TypeElement foundAnnotations,
                                           final RoundEnvironment roundEnv) {
    final Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(foundAnnotations);
    for (final Element e : elements) {
      // should always be METHOD, but just being paranoid
      if (e.getKind() != ElementKind.METHOD) {
        continue;
      }
      final ExecutableElement ee = (ExecutableElement) e;
      final List<ResourceArgument> arguments = computeMethodArguments(ee);
      final ResourceMethod method = computeMethod(ee, arguments);
      final ResourceClass klass = getParentResourceClass(e);
      klass.getMembers().add(method);
    }
  }

  /**
   * Given an {@link ExecutableElement} representing the method, compute it's arguments.
   */
  private List<ResourceArgument> computeMethodArguments(final ExecutableElement ee) {
    final List<ResourceArgument> arguments = Lists.newArrayList();
    for (final VariableElement ve : ee.getParameters()) {
      final PathParam pathAnnotation = ve.getAnnotation(PathParam.class);
      final String argName;
      if (pathAnnotation != null) {
        argName = pathAnnotation.value();
      } else {
        argName = ve.getSimpleName().toString();
      }
      final ArgumentDoc argDoc = ve.getAnnotation(ArgumentDoc.class);
      final String docString;
      if (argDoc != null) {
        docString = argDoc.value();
      } else {
        docString = null;
      }
      final ResourceArgument.Location location;
      if (pathAnnotation != null) {
        location = Location.PATH;
      } else if (ve.getAnnotation(QueryParam.class) != null) {
        location = Location.QUERY;
      } else if (ve.getAnnotation(Context.class) != null) {
        location = Location.CONTEXT;
      } else {
        location = Location.BODY;
      }
      arguments.add(new ResourceArgument(argName, makeTypeDescriptor(ve.asType()), docString,
          location));
    }
    return arguments;
  }

  /**
   * Given an {@link ExecutableElement} representing the method, and the already computed list
   * of arguments to the method, produce a {@link ResourceMethod}.
   */
  private ResourceMethod computeMethod(ExecutableElement ee, List<ResourceArgument> arguments) {
    final String javaDoc = processingEnv.getElementUtils().getDocComment(ee);
    final Path pathAnnotation = ee.getAnnotation(Path.class);
    final Produces producesAnnotation = ee.getAnnotation(Produces.class);
    final Consumes consumesAnnotation = ee.getAnnotation(Consumes.class);
    final ExampleResponse exampleResponse = ee.getAnnotation(ExampleResponse.class);
    final ExampleRequest exampleRequest = ee.getAnnotation(ExampleRequest.class);
    final ExampleArgs exampleArgs = ee.getAnnotation(ExampleArgs.class);
    final Map<String, String> parsedArgs = parseArgs(exampleArgs);

    return new ResourceMethod(
        ee.getSimpleName().toString(),
        computeRequestMethod(ee),
        (pathAnnotation == null) ? null : pathAnnotation.value(),
        (producesAnnotation == null) ? null : Joiner.on(",").join(producesAnnotation.value()),
        (consumesAnnotation == null) ? null : Joiner.on(",").join(consumesAnnotation.value()),
        makeTypeDescriptor(ee.getReturnType()),
        arguments,
        javaDoc,
        (exampleResponse == null) ? null : exampleResponse.value(),
        (exampleRequest == null) ? null : exampleRequest.value(),
        parsedArgs);
  }

  private Map<String, String> parseArgs(final ExampleArgs exampleArgs) {
    if (exampleArgs == null) {
      return null;
    }
    final ImmutableMap.Builder<String, String> result = ImmutableMap.builder();
    final List<String> items = Splitter.on("|").splitToList(exampleArgs.value());
    for (final String item : items) {
      final List<String> bits = Splitter.on("=").limit(2).splitToList(item);
      if (bits.size() != 2) {
        throw new RuntimeException("item in " + exampleArgs.value() + " missing = sign?");
      }
      result.put(bits.get(0), bits.get(1));
    }
    return result.build();
  }

  /**
   * Find the request method annotation the method was annotated with and return a string
   * representing the request method.
   */
  private String computeRequestMethod(Element e) {
    for (AnnotationMirror am : e.getAnnotationMirrors()) {
      final String typeString = am.getAnnotationType().toString();
      if (typeString.endsWith(".GET")) {
        return "GET";
      } else if (typeString.endsWith(".PUT")) {
        return "PUT";
      } else if (typeString.endsWith(".POST")) {
        return "POST";
      } else if (typeString.endsWith(".PATCH")) {
        return "PATCH";
      } else if (typeString.endsWith(".DELETE")) {
        return "DELETE";
      }
    }

    return null;
  }

  /**
   * Given an {@link Element} representing the method get either a cached {@link ResourceClass} or
   * produce a new one.
   */
  private ResourceClass getParentResourceClass(final Element e) {
    final String parentClassName = e.getEnclosingElement().toString();
    final ResourceClass klass = resourceClasses.get(parentClassName);
    if (klass != null) {
      return klass;
    }

    final Path klassPath = e.getEnclosingElement().getAnnotation(Path.class);
    final ResourceClass newKlass = new ResourceClass(
        (klassPath == null) ? null : klassPath.value(),
        Lists.<ResourceMethod>newArrayList());
    resourceClasses.put(parentClassName, newKlass);
    return newKlass;
  }

  private void processJacksonAnnotations(final RoundEnvironment roundEnv) {
    processJsonPropertyAnnotations(roundEnv);
    processJsonSerializeAnnotations(roundEnv);
    processDocEnumAnnotations(roundEnv);
  }

  private void processDocEnumAnnotations(final RoundEnvironment roundEnv) {
    final Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(DocEnum.class);
    for (final Element e : elements) {
      if (e.getKind() != ElementKind.ENUM) {
        System.err.println("[WARN] Kind for " + e.getSimpleName()
            + " is not ENUM like we expected");
        continue;
      }
      final TypeElement enumElement = (TypeElement) e;
      final TransferClass klass = getOrCreateTransferClass(
          enumElement.getQualifiedName().toString(),
          processingEnv.getElementUtils().getDocComment(enumElement));
      final List<? extends Element> enclosed = e.getEnclosedElements();
      for (final Element inner : enclosed) {
        if (inner.getKind() == ElementKind.ENUM_CONSTANT) {
          klass.getValues().add(new TransferEnumValue(inner.getSimpleName().toString(),
              processingEnv.getElementUtils().getDocComment(inner)));
        }
      }
    }
  }

  private String getJavaDoc(final TypeElement clazz) {
    final String clazzDoc = processingEnv.getElementUtils().getDocComment(clazz);
    if (clazzDoc != null) {
      return clazzDoc;
    }

    return null;
  }


  /**
   * Go through a Jackson-annotated constructor, and produce {@link TransferClass}es representing
   * what we found.
   */
  private void processJsonPropertyAnnotations(final RoundEnvironment roundEnv) {
    final Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(JsonProperty.class);
    for (final Element e : elements) {
      if (e.getEnclosingElement() == null) {
        continue;
      }
      final Element parentElement = e.getEnclosingElement().getEnclosingElement();
      if (parentElement == null) {
        continue;
      }
      if (!(parentElement instanceof TypeElement)) {
        continue;
      }
      final TypeElement parent = (TypeElement) parentElement;
      final TransferClass klass = getOrCreateTransferClass(
          parent.getQualifiedName().toString(), getJavaDoc(parent));

      klass.add(e.toString(), makeTypeDescriptor(e.asType()));
    }
  }

  /**
   * If we see one of these, just create an entry that the class exists (with it's javadoc),
   * but don't try to do anything fancy.
   */
  private void processJsonSerializeAnnotations(final RoundEnvironment roundEnv) {
    final Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(JsonSerialize.class);
    for (final Element e : elements) {
      if (e.getKind() != ElementKind.CLASS) {
        debugMessages.add("kind for " + e + " is not CLASS, but " + e.getKind());
        continue;
      }

      final TypeElement te = (TypeElement) e;
      final String className = te.getQualifiedName().toString();
      if (jsonClasses.containsKey(className)) {
        // it has already been processed by other means
        continue;
      }

      getOrCreateTransferClass(className, processingEnv.getElementUtils().getDocComment(te));
    }
  }

  private TransferClass getOrCreateTransferClass(final String parentName,
      final String parentJavaDoc) {
    return getOrCreateTransferClass(parentName, parentJavaDoc, (String) null);
  }

  private TransferClass getOrCreateTransferClass(final String parentName,
                                                 final String parentJavaDoc,
                                                 final String logInfo) {
    final TransferClass klass = jsonClasses.get(parentName);
    if (klass != null) {
      return klass;
    }
    final TransferClass newKlass = new TransferClass(Lists.<TransferEnumValue>newArrayList(),
        Lists.<TransferMember>newArrayList(), parentJavaDoc, logInfo);
    jsonClasses.put(parentName, newKlass);
    return newKlass;
  }


  /**
   * Make a {@link TypeDescriptor} by examining the {@link TypeMirror} and recursively looking
   * at the generic arguments to the type (if they exist).
   */
  private TypeDescriptor makeTypeDescriptor(final TypeMirror type) {
    if (type.getKind() != TypeKind.DECLARED) {
      return new TypeDescriptor(type.toString(), ImmutableList.<TypeDescriptor>of());
    }
    final DeclaredType dt = (DeclaredType) type;

    final String plainType = processingEnv.getTypeUtils().erasure(type).toString();
    final List<TypeDescriptor> typeArgumentsList = Lists.newArrayList();
    final List<? extends TypeMirror> typeArguments = dt.getTypeArguments();
    for (final TypeMirror arg : typeArguments) {
      typeArgumentsList.add(makeTypeDescriptor(arg));
    }
    return new TypeDescriptor(plainType, typeArgumentsList);
  }

  private void fatalError(String msg) {
    processingEnv.getMessager().printMessage(Kind.ERROR, "FATAL ERROR: " + msg);
  }

  /**
   * Dump the contents of our discoveries.
   */
  private void generateOutput() {
    final Filer filer = processingEnv.getFiler();
    writeJsonToFile(filer, "JSONClasses", jsonClasses);
    writeJsonToFile(filer, "debugcrud", debugMessages);

    final List<ResourceMethod> resources = Lists.newArrayList();
    for (ResourceClass klass : resourceClasses.values()) {
      final String path = klass.getPath();

      for (final ResourceMethod method : klass.getMembers()) {
        resources.add(new ResourceMethod("", method.getMethod(),
            computeDisplayPath(path, method.getPath()),
            method.getReturnContentType(), method.getConsumesContentType(),
            method.getReturnType(), method.getArguments(),
            method.getJavadoc(), method.getExampleResponse(),
            method.getExampleRequest(),
            method.getExampleArgs()));
      }
    }
    writeJsonToFile(filer, "RESTEndpoints", resources);
  }

  private String computeDisplayPath(String path, String methodPath) {
    final String rootPath;
    if (!path.startsWith("/")) {
      rootPath = "/" + path;
    } else {
      rootPath = path;
    }

    if (methodPath == null) {
      return rootPath;
    }
    // if there is a delimiting slash between the two of them, just join directly
    if (rootPath.endsWith("/") != methodPath.startsWith("/")) {
      return rootPath + methodPath;
    }
    // two slashes, trim off one
    if (rootPath.endsWith("/")) {
      return rootPath + methodPath.substring(1);
    }
    // no slashes, add one
    return rootPath + "/" + methodPath;
  }

  private void writeJsonToFile(Filer filer, String resourceFile, Object obj) {
    try {
      final FileObject outputFile = filer.createResource(StandardLocation.CLASS_OUTPUT, "",
          resourceFile);
      try (final OutputStream out = outputFile.openOutputStream()) {
        out.write(NORMALIZING_OBJECT_WRITER.writeValueAsBytes(obj));
      }
    } catch (IOException e) {
      fatalError("Failed writing to " + resourceFile + "\n");
      e.printStackTrace();
    }
  }
}
