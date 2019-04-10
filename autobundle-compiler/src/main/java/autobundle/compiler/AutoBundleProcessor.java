package autobundle.compiler;

import com.google.auto.service.AutoService;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeName;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;

import autobundle.annotation.BooleanArrayValue;
import autobundle.annotation.BooleanValue;
import autobundle.annotation.ByteArrayValue;
import autobundle.annotation.ByteValue;
import autobundle.annotation.CharArrayValue;
import autobundle.annotation.CharSequenceArrayListValue;
import autobundle.annotation.CharSequenceArrayValue;
import autobundle.annotation.CharSequenceValue;
import autobundle.annotation.CharValue;
import autobundle.annotation.DoubleArrayValue;
import autobundle.annotation.DoubleValue;
import autobundle.annotation.FloatArrayValue;
import autobundle.annotation.FloatValue;
import autobundle.annotation.IntArrayValue;
import autobundle.annotation.IntValue;
import autobundle.annotation.IntegerArrayListValue;
import autobundle.annotation.LongArrayValue;
import autobundle.annotation.LongValue;
import autobundle.annotation.ParcelableArrayListValue;
import autobundle.annotation.ParcelableArrayValue;
import autobundle.annotation.ParcelableValue;
import autobundle.annotation.Required;
import autobundle.annotation.SerializableValue;
import autobundle.annotation.ShortArrayValue;
import autobundle.annotation.ShortValue;
import autobundle.annotation.SparseParcelableArrayValue;
import autobundle.annotation.StringArrayListValue;
import autobundle.annotation.StringArrayValue;
import autobundle.annotation.StringValue;

import static javax.lang.model.element.ElementKind.CLASS;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.STATIC;

/**
 * 创建时间：2019/4/4
 * 编写人： chengxin
 * 功能描述：
 */
@AutoService(Processor.class)
public class AutoBundleProcessor extends AbstractProcessor {
    private static final List<Class<? extends Annotation>> ANNOTATIONS = Arrays.asList(//
            BooleanArrayValue.class, //
            BooleanValue.class, //
            ByteArrayValue.class, //
            ByteValue.class, //
            CharArrayValue.class, //
            CharSequenceArrayListValue.class, //
            CharSequenceArrayValue.class, //
            CharSequenceValue.class, //
            CharValue.class, //
            DoubleArrayValue.class, //
            DoubleValue.class, //
            FloatArrayValue.class,
            FloatValue.class,
            IntArrayValue.class,
            IntegerArrayListValue.class,
            IntValue.class,
            LongArrayValue.class,
            LongValue.class,
            ParcelableArrayListValue.class,
            ParcelableArrayValue.class,
            ParcelableValue.class,
            SerializableValue.class,
            ShortArrayValue.class,
            ShortValue.class,
            SparseParcelableArrayValue.class,
            StringArrayListValue.class,
            StringArrayValue.class,
            StringValue.class
    );
    private Types typeUtils;
    private Filer filer;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        typeUtils = env.getTypeUtils();
        filer = env.getFiler();
        int.class.getName();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Map<TypeElement, BundleSet> bindingMap = findAndParseTargets(roundEnv);
        for (Map.Entry<TypeElement, BundleSet> entry : bindingMap.entrySet()) {
            TypeElement typeElement = entry.getKey();
            BundleSet binding = entry.getValue();
            JavaFile javaFile = binding.brewJava();
            try {
                javaFile.writeTo(filer);
            } catch (IOException e) {
                error(typeElement, "Unable to write binding for type %s: %s", typeElement, e.getMessage());
            }
        }

        return false;
    }

    private Map<TypeElement, BundleSet> findAndParseTargets(RoundEnvironment env) {
        Map<TypeElement, BundleSet.Builder> builderMap = new LinkedHashMap<>();
        Set<TypeElement> erasedTargetNames = new LinkedHashSet<>();

        for (Class<? extends Annotation> annotationClass : ANNOTATIONS) {
            // Process each @BindView element.
            for (Element element : env.getElementsAnnotatedWith(annotationClass)) {

                Element enclosingElement = element.getEnclosingElement();
                //element:VariableElement
                //method parameter:Symbol$MethodSymbol->ExecutableElement
                if (enclosingElement instanceof ExecutableElement) {
                    continue;
                }
                // we don't SuperficialValidation.validateElement(element)
                // so that an unresolved View type can be generated by later processing rounds
                try {
                    parseAnnotations(element, builderMap, erasedTargetNames, annotationClass);
                } catch (Exception e) {
                    logParsingError(element, annotationClass, e);
                }
            }
        }

        // Associate superclass binders with their subclass binders. This is a queue-based tree walk
        // which starts at the roots (superclasses) and walks to the leafs (subclasses).
        Deque<Map.Entry<TypeElement, BundleSet.Builder>> entries =
                new ArrayDeque<>(builderMap.entrySet());
        Map<TypeElement, BundleSet> bindingMap = new LinkedHashMap<>();
        while (!entries.isEmpty()) {
            Map.Entry<TypeElement, BundleSet.Builder> entry = entries.removeFirst();

            TypeElement type = entry.getKey();
            BundleSet.Builder builder = entry.getValue();

            TypeElement parentType = findParentType(type, erasedTargetNames);
            if (parentType == null) {
                bindingMap.put(type, builder.build());
            } else {
                BundleSet parentBinding = bindingMap.get(parentType);
                if (parentBinding != null) {
                    builder.setParent(parentBinding);
                    bindingMap.put(type, builder.build());
                } else {
                    // Has a superclass binding but we haven't built it yet. Re-enqueue for later.
                    entries.addLast(entry);
                }
            }
        }
        return bindingMap;
    }

    private void parseAnnotations(Element element, Map<TypeElement, BundleSet.Builder> builderMap,
                                  Set<TypeElement> erasedTargetNames, Class<? extends Annotation> annotationClass) throws Exception {
        TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();
        // Start by verifying common generated code restrictions.
        boolean hasError = isInaccessibleViaGeneratedCode(annotationClass, "fields", element)
                || isBindingInWrongPackage(annotationClass, element);
        TypeMirror elementType = element.asType();
        if (hasError)
            return;
        Name simpleName = element.getSimpleName();
        String name = simpleName.toString();
        // Assemble information on the field.
        Annotation annotation = element.getAnnotation(annotationClass);
        Method annotationValue = annotationClass.getDeclaredMethod("value");
        // Bundle -> key
        String value = (String) annotationValue.invoke(annotation);
        boolean required = element.getAnnotation(Required.class) != null;
        BundleSet.Builder builder = builderMap.get(enclosingElement);
        if (builder != null) {
            FieldBundleBinding existingBinding = builder.findExistingBindingByValue(value);
            if (existingBinding != null) {
                error(element, "Attempt to use @%s for an already bound value %s on '%s'. (%s.%s)",
                        annotationClass.getSimpleName(), value, existingBinding.name,
                        enclosingElement.getQualifiedName(), element.getSimpleName());
                return;
            }
            existingBinding = builder.findExistingBindingByName(name);
            if (existingBinding != null) {
                error(element, "Attempt to use @%s for an already bound annotation @%s on '%s.%s'",
                        annotationClass.getSimpleName(), existingBinding.annotationClass.getSimpleName(),
                        enclosingElement.getQualifiedName(), existingBinding.name);
                return;
            }
        } else {
            builder = BundleSet.newBuilder(enclosingElement);
            builderMap.put(enclosingElement, builder);
        }

        TypeName type = TypeName.get(elementType);
        builder.addField(new FieldBundleBinding(name, annotationClass, value, type, required));
        // Add the type-erased version to the valid binding targets set.
        erasedTargetNames.add(enclosingElement);
    }

    /**
     * Finds the parent binder type in the supplied set, if any.
     */
    private @Nullable
    TypeElement findParentType(TypeElement typeElement, Set<TypeElement> parents) {
        TypeMirror type;
        while (true) {
            type = typeElement.getSuperclass();
            if (type.getKind() == TypeKind.NONE) {
                return null;
            }
            typeElement = (TypeElement) ((DeclaredType) type).asElement();
            if (parents.contains(typeElement)) {
                return typeElement;
            }
        }
    }

    private boolean isInaccessibleViaGeneratedCode(Class<? extends Annotation> annotationClass,
                                                   String targetThing, Element element) {
        boolean hasError = false;
        TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

        // Verify field or method modifiers.
        Set<Modifier> modifiers = element.getModifiers();
        if (modifiers.contains(PRIVATE) || modifiers.contains(STATIC)) {
            error(element, "@%s %s must not be private or static. (%s.%s)",
                    annotationClass.getSimpleName(), targetThing, enclosingElement.getQualifiedName(),
                    element.getSimpleName());
            hasError = true;
        }

        // Verify containing type.
        if (enclosingElement.getKind() != CLASS) {
            error(element, "@%s %s may only be contained in classes. (%s.%s)",
                    annotationClass.getSimpleName(), targetThing, enclosingElement.getQualifiedName(),
                    element.getSimpleName());
            hasError = true;
        }

        // Verify containing class visibility is not private.
        if (enclosingElement.getModifiers().contains(PRIVATE)) {
            error(element, "@%s %s may not be contained in private classes. (%s.%s)",
                    annotationClass.getSimpleName(), targetThing, enclosingElement.getQualifiedName(),
                    element.getSimpleName());
            hasError = true;
        }

        return hasError;
    }

    private boolean isBindingInWrongPackage(Class<? extends Annotation> annotationClass,
                                            Element element) {
        TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();
        String qualifiedName = enclosingElement.getQualifiedName().toString();

        if (qualifiedName.startsWith("android.")) {
            error(element, "@%s-annotated class incorrectly in Android framework package. (%s)",
                    annotationClass.getSimpleName(), qualifiedName);
            return true;
        }
        if (qualifiedName.startsWith("java.")) {
            error(element, "@%s-annotated class incorrectly in Java framework package. (%s)",
                    annotationClass.getSimpleName(), qualifiedName);
            return true;
        }

        return false;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> types = new LinkedHashSet<>();
        for (Class<? extends Annotation> annotationClass : ANNOTATIONS) {
            types.add(annotationClass.getCanonicalName());
        }
        return types;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    private void logParsingError(Element element, Class<? extends Annotation> annotation,
                                 Exception e) {
        StringWriter stackTrace = new StringWriter();
        e.printStackTrace(new PrintWriter(stackTrace));
        error(element, "Unable to parse @%s binding.\n\n%s", annotation.getSimpleName(), stackTrace);
    }


    private void error(Element element, String message, Object... args) {
        printMessage(Kind.ERROR, element, message, args);
    }

    private void note(Element element, String message, Object... args) {
        printMessage(Kind.NOTE, element, message, args);
    }

    private void note(String message, Object... args) {
        if (args.length > 0) {
            message = String.format(message, args);
        }
        processingEnv.getMessager().printMessage(Kind.NOTE, message);
    }

    private void printMessage(Kind kind, Element element, String message, Object[] args) {
        if (args.length > 0) {
            message = String.format(message, args);
        }

        processingEnv.getMessager().printMessage(kind, message, element);
    }
}
