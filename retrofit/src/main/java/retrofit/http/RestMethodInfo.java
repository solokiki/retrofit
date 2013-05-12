// Copyright 2013 Square, Inc.
package retrofit.http;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Request metadata about a service interface declaration. */
final class RestMethodInfo {
  static final int NO_BODY = -1;

  // Matches strings containing lowercase characters, digits, underscores, or hyphens that start
  // with a lowercase character in between '{' and '}'.
  private static final Pattern PATH_PARAMETERS = Pattern.compile("\\{([a-z][a-z0-9_-]*)}");

  enum RequestType {
    /** No content-specific logic required. */
    SIMPLE,
    /** Multi-part request body. */
    MULTIPART,
    /** Form-encoded request body. */
    FORM_ENCODED
  }

  final Method method;

  boolean loaded = false;

  // Method-level details
  final boolean isSynchronous;
  Type responseObjectType;
  RequestType requestType = RequestType.SIMPLE;
  String requestMethod;
  boolean requestHasBody;
  String requestUrl;
  Set<String> requestUrlParamNames;
  String requestQuery;
  List<retrofit.http.client.Header> headers;

  // Parameter-level details
  String[] requestUrlParam;
  String[] requestQueryName;
  boolean hasQueryParams = false;
  String[] requestFormPair;
  String[] requestMultipartPart;
  String[] requestParamHeader;
  int bodyIndex = NO_BODY;

  RestMethodInfo(Method method) {
    this.method = method;
    isSynchronous = parseResponseType();
  }

  synchronized void init() {
    if (loaded) return;

    parseMethodAnnotations();
    parseParameters();

    loaded = true;
  }

  /** Loads {@link #requestMethod} and {@link #requestType}. */
  private void parseMethodAnnotations() {
    for (Annotation methodAnnotation : method.getAnnotations()) {
      Class<? extends Annotation> annotationType = methodAnnotation.annotationType();
      RestMethod methodInfo = null;

      // Look for a @RestMethod annotation on the parameter annotation indicating request method.
      for (Annotation innerAnnotation : annotationType.getAnnotations()) {
        if (RestMethod.class == innerAnnotation.annotationType()) {
          methodInfo = (RestMethod) innerAnnotation;
          break;
        }
      }

      if (methodInfo != null) {
        if (requestMethod != null) {
          throw new IllegalArgumentException("Method "
              + method.getName()
              + " contains multiple HTTP methods. Found: "
              + requestMethod
              + " and "
              + methodInfo.value());
        }
        String path;
        try {
          path = (String) annotationType.getMethod("value").invoke(methodAnnotation);
        } catch (Exception e) {
          throw new RuntimeException("Failed to extract path from "
              + annotationType.getSimpleName()
              + " annotation on "
              + method.getName()
              + ".", e);
        }
        parsePath(path);
        requestMethod = methodInfo.value();
        requestHasBody = methodInfo.hasBody();
      } else if (annotationType == Headers.class) {
        String[] headersToParse = ((Headers) methodAnnotation).value();
        if (headersToParse.length == 0) {
          throw new IllegalStateException("Headers annotation was empty.");
        }
        headers = parseHeaders(headersToParse);
      } else if (annotationType == Multipart.class) {
        requestType = RequestType.MULTIPART;
      } else if (annotationType == FormEncoded.class) {
        requestType = RequestType.FORM_ENCODED;
      }
    }

    if (requestMethod == null) {
      throw new IllegalStateException(
          "Method " + method.getName() + " not annotated with request type (e.g., GET, POST).");
    }
    if (!requestHasBody) {
      if (requestType == RequestType.MULTIPART) {
        throw new IllegalStateException(
            "Multipart can only be specific on HTTP methods with request body (e.g., POST). ("
                + method.getName()
                + ")");
      }
      if (requestType == RequestType.FORM_ENCODED) {
        throw new IllegalStateException(
            "Multipart can only be specific on HTTP methods with request body (e.g., POST). ("
                + method.getName()
                + ")");
      }
    }
  }

  /** Loads {@link #requestUrl}, {@link #requestUrlParamNames}, and {@link #requestQuery}. */
  private void parsePath(String path) {
    if (path == null || path.length() == 0 || path.charAt(0) != '/') {
      throw new IllegalArgumentException("URL path \""
          + path
          + "\" on method "
          + method.getName()
          + " must start with '/'. ("
          + method.getName()
          + ")");
    }

    // Get the relative URL path and existing query string, if present.
    String url = path;
    String query = null;
    int question = path.indexOf('?');
    if (question != -1 && question < path.length() - 1) {
      url = path.substring(0, question);
      query = path.substring(question + 1);
      hasQueryParams = true;

      // Ensure the query string does not have any named parameters.
      Matcher queryMatcher = PATH_PARAMETERS.matcher(query);
      if (queryMatcher.find()) {
        List<String> matches = new ArrayList<String>();
        do {
          matches.add("{" + queryMatcher.group(1) + "}");
        } while (queryMatcher.find());
        throw new IllegalStateException("URL query string \""
            + query
            + "\" on method "
            + method.getName()
            + " may not have replace block. Found: "
            + matches);
      }
    }

    Set<String> urlParams = parsePathParameters(path);

    requestUrl = url;
    requestUrlParamNames = urlParams;
    requestQuery = query;
  }

  private List<retrofit.http.client.Header> parseHeaders(String[] headersToParse) {
    List<retrofit.http.client.Header> headers = new ArrayList<retrofit.http.client.Header>();
    for (String headerToParse: headersToParse) {
      int colon = headerToParse.indexOf(':');
      headers.add(new retrofit.http.client.Header(headerToParse.substring(0, colon),
          headerToParse.substring(colon + 2)));
    }
    return headers;
  }

  /** Loads {@link #responseObjectType}. Returns {@code true} if method is synchronous. */
  private boolean parseResponseType() {
    // Synchronous methods have a non-void return type.
    Type returnType = method.getGenericReturnType();

    // Asynchronous methods should have a Callback type as the last argument.
    Type lastArgType = null;
    Class<?> lastArgClass = null;
    Type[] parameterTypes = method.getGenericParameterTypes();
    if (parameterTypes.length > 0) {
      Type typeToCheck = parameterTypes[parameterTypes.length - 1];
      lastArgType = typeToCheck;
      if (typeToCheck instanceof ParameterizedType) {
        typeToCheck = ((ParameterizedType) typeToCheck).getRawType();
      }
      if (typeToCheck instanceof Class) {
        lastArgClass = (Class<?>) typeToCheck;
      }
    }

    boolean hasReturnType = returnType != void.class;
    boolean hasCallback = lastArgClass != null && Callback.class.isAssignableFrom(lastArgClass);

    // Check for invalid configurations.
    if (hasReturnType && hasCallback) {
      throw new IllegalArgumentException("Method "
          + method.getName()
          + " may only have return type or Callback as last argument, not both.");
    }
    if (!hasReturnType && !hasCallback) {
      throw new IllegalArgumentException("Method "
          + method.getName()
          + " must have either a return type or Callback as last argument.");
    }

    if (hasReturnType) {
      responseObjectType = returnType;
      return true;
    }

    lastArgType = Types.getSupertype(lastArgType, Types.getRawType(lastArgType), Callback.class);
    if (lastArgType instanceof ParameterizedType) {
      Type[] types = ((ParameterizedType) lastArgType).getActualTypeArguments();
      for (int i = 0; i < types.length; i++) {
        Type type = types[i];
        if (type instanceof WildcardType) {
          types[i] = ((WildcardType) type).getUpperBounds()[0];
        }
      }
      responseObjectType = types[0];
      return false;
    }

    throw new IllegalArgumentException("Last parameter of "
        + method.getName()
        + " must be of type Callback<X> or Callback<? super X>. Found: "
        + lastArgType);
  }

  /**
   * Loads {@link #requestUrlParam}, {@link #requestQueryName}, {@link #requestFormPair},
   * {@link #requestMultipartPart}, and {@link #requestParamHeader}. Must be called after
   * {@link #parseMethodAnnotations()}.
   */
  private void parseParameters() {
    Class<?>[] parameterTypes = method.getParameterTypes();

    Annotation[][] parameterAnnotationArrays = method.getParameterAnnotations();
    int count = parameterAnnotationArrays.length;
    if (!isSynchronous) {
      count -= 1; // Callback is last argument when not a synchronous method.
    }

    String[] urlParam = new String[count];
    String[] queryName = new String[count];
    String[] formValue = new String[count];
    String[] multipartPart = new String[count];
    String[] paramHeader = new String[count];
    boolean gotPair = false;
    boolean gotPart = false;

    for (int i = 0; i < count; i++) {
      boolean hasRetrofitAnnotation = false;

      Class<?> parameterType = parameterTypes[i];
      Annotation[] parameterAnnotations = parameterAnnotationArrays[i];
      if (parameterAnnotations != null) {
        for (Annotation parameterAnnotation : parameterAnnotations) {
          Class<? extends Annotation> annotationType = parameterAnnotation.annotationType();

          if (annotationType == Path.class) {
            hasRetrofitAnnotation = true;
            String name = ((Path) parameterAnnotation).value();

            // Verify URL replacement name is actually present in the URL path.
            if (!requestUrlParamNames.contains(name)) {
              throw new IllegalStateException(
                  "Method path \"" + requestUrl + "\" does not contain {" + name + "}.");
            }

            urlParam[i] = name;
          } else if (annotationType == Query.class) {
            hasRetrofitAnnotation = true;
            hasQueryParams = true;
            String name = ((Query) parameterAnnotation).value();

            // TODO verify query name not already used in URL?

            queryName[i] = name;
          } else if (annotationType == Header.class) {
            String name = ((Header) parameterAnnotation).value();
            if (parameterType != String.class) {
              throw new IllegalStateException("@Header parameter type must be String: " + name);
            }

            // TODO verify name not already used?

            hasRetrofitAnnotation = true;
            paramHeader[i] = name;
          } else if (annotationType == Pair.class) {
            if (requestType != RequestType.FORM_ENCODED) {
              throw new IllegalStateException(
                  "@Pair parameters can only be used with form encoding.");
            }

            gotPair = true;
            hasRetrofitAnnotation = true;
            String name = ((Pair) parameterAnnotation).value();

            // TODO verify name not already used?

            formValue[i] = name;
          } else if (annotationType == Part.class) {
            if (requestType != RequestType.MULTIPART) {
              throw new IllegalStateException(
                  "@Part parameters can only be used with multipart encoding.");
            }

            gotPart = true;
            hasRetrofitAnnotation = true;
            String name = ((Part) parameterAnnotation).value();

            // TODO verify name not already used?

            multipartPart[i] = name;
          } else if (annotationType == Body.class) {
            if (requestType != RequestType.SIMPLE) {
              throw new IllegalStateException(
                  "@Body parameters cannot be used with form or multi-part encoding.");
            }
            if (bodyIndex != NO_BODY) {
              throw new IllegalStateException(
                  "Method annotated with multiple Body method annotations: " + method);
            }

            hasRetrofitAnnotation = true;
            bodyIndex = i;
          }
        }
      }

      if (!hasRetrofitAnnotation) {
        throw new IllegalStateException(
            "No annotations found on parameter " + (i + 1) + " of " + method.getName());
      }
    }

    if (requestType == RequestType.SIMPLE && !requestHasBody && bodyIndex != NO_BODY) {
      throw new IllegalStateException("Non-body HTTP method cannot contain @Body or @TypedOutput.");
    }
    if (requestType == RequestType.FORM_ENCODED && !gotPair) {
      throw new IllegalStateException("Form-encoded method must contain at least one @Pair.");
    }
    if (requestType == RequestType.MULTIPART && !gotPart) {
      throw new IllegalStateException("Multipart method must contain at least one @Part.");
    }

    requestUrlParam = urlParam;
    requestQueryName = queryName;
    requestFormPair = formValue;
    requestMultipartPart = multipartPart;
    requestParamHeader = paramHeader;
  }

  /**
   * Gets the set of unique path parameters used in the given URI. If a parameter is used twice
   * in the URI, it will only show up once in the set.
   */
  static Set<String> parsePathParameters(String path) {
    Matcher m = PATH_PARAMETERS.matcher(path);
    Set<String> patterns = new LinkedHashSet<String>();
    while (m.find()) {
      patterns.add(m.group(1));
    }
    return patterns;
  }
}
