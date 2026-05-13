package org.emi.json;

import java.lang.classfile.*;
import java.lang.reflect.AccessFlag;
import java.lang.constant.*;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;

/**
 * Generador de bytecode dinámico para la serialización de alto rendimiento.
 * Genera implementaciones de {@link RecordSerializer} que llaman directamente
 * a los accessors del Record sin usar reflexión.
 * 
 * <p>Ejemplo de uso interno:</p>
 * <pre>{@code
 * RecordSerializer ser = SerializerGenerator.generateSerializer(User.class, false);
 * ser.serialize(sb, userInstance);
 * }</pre>
 */
public final class SerializerGenerator {

    private SerializerGenerator() {}

    /**
     * Genera una implementación de {@link RecordSerializer} optimizada para el Record dado.
     * Crea una "Hidden Class" que invoca directamente a los métodos de acceso del Record.
     * 
     * @param recordClass La clase del Record a serializar.
     * @param prettyPrint Indica si se debe generar código con saltos de línea e indentación.
     * @return Una instancia del serializador generado por bytecode.
     * 
     * <p>Ejemplo de uso interno:</p>
     * <pre>{@code
     * RecordSerializer ser = SerializerGenerator.generateSerializer(User.class, true);
     * ser.serialize(sb, userInstance);
     * }</pre>
     */
    public static RecordSerializer generateSerializer(Class<?> recordClass, boolean prettyPrint) {
        ClassFile cf = ClassFile.of();
        ClassDesc recordDesc = ClassDesc.of(recordClass.getName());
        ClassDesc serializerInterface = ClassDesc.of(RecordSerializer.class.getName());
        ClassDesc sbDesc = ClassDesc.of(StringBuilder.class.getName());
        
        // Nombre de la clase generada
        String suffix = prettyPrint ? "PrettySerializer" : "CompactSerializer";
        ClassDesc generatedDesc = recordDesc.nested(suffix);

        byte[] classBytes = cf.build(generatedDesc, clb -> {
            clb.withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL);
            clb.withInterfaceSymbols(serializerInterface);

            // 1. Constructor: public <init>() { super(); }
            clb.withMethod(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, AccessFlag.PUBLIC.mask(), mb -> {
                mb.withCode(cob -> {
                    cob.aload(0);
                    cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                    cob.return_();
                });
            });

            // 2. Método: public void serialize(StringBuilder sb, Object record)
            clb.withMethod("serialize", 
                MethodTypeDesc.of(ConstantDescs.CD_void, sbDesc, ConstantDescs.CD_Object), 
                AccessFlag.PUBLIC.mask(), mb -> {
                mb.withCode(cob -> {
                    RecordComponent[] components = recordClass.getRecordComponents();
                    String open  = prettyPrint ? "{\n" : "{";
                    String close = prettyPrint ? "\n}" : "}";
                    String sep   = prettyPrint ? ",\n" : ",";
                    String indent = prettyPrint ? "  " : "";

                    // sb.append(open)
                    appendStringConstant(cob, open);

                    for (int i = 0; i < components.length; i++) {
                        if (i > 0) appendStringConstant(cob, sep);

                        String key = indent + "\"" + components[i].getName() + "\": " + (prettyPrint ? "" : "");
                        if (!prettyPrint) key = "\"" + components[i].getName() + "\":";
                        
                        appendStringConstant(cob, key);

                        // 1. Cargar StringBuilder para el append posterior
                        cob.aload(1);

                        // 2. Cargar record y llamar al accessor
                        cob.aload(2); // record (Object)
                        cob.checkcast(recordDesc);
                        
                        Method accessor = components[i].getAccessor();
                        Class<?> type = accessor.getReturnType();
                        cob.invokevirtual(recordDesc, accessor.getName(), 
                            MethodTypeDesc.of(toClassDesc(type)));

                        // 3. Llamar al append o helper
                        emitAppendValue(cob, type);
                    }

                    appendStringConstant(cob, close);
                    cob.return_();
                });
            });
        });

        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(recordClass, MethodHandles.lookup());
            Class<?> hiddenClass = lookup.defineHiddenClass(classBytes, true, MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
            return (RecordSerializer) hiddenClass.getConstructor().newInstance();
        } catch (Exception e) {
            throw new JsonMappingException("Error generando serializador para " + recordClass.getName(), e);
        }
    }

    private static void appendStringConstant(CodeBuilder cob, String s) {
        cob.aload(1); // sb
        cob.ldc(s);
        cob.invokevirtual(ClassDesc.of(StringBuilder.class.getName()), "append", 
            MethodTypeDesc.of(ClassDesc.of(StringBuilder.class.getName()), ConstantDescs.CD_String));
        cob.pop(); // discard returned sb
    }

    private static void emitAppendValue(CodeBuilder cob, Class<?> type) {
        ClassDesc sbDesc = ClassDesc.of(StringBuilder.class.getName());
        if (type.isPrimitive()) {
            // Stack ya tiene [sb, valor]
            ClassDesc paramDesc = toClassDesc(type);
            // StringBuilder no tiene append(short) ni append(byte), se usan como int.
            if (type == short.class || type == byte.class) paramDesc = ConstantDescs.CD_int;

            cob.invokevirtual(sbDesc, "append", 
                MethodTypeDesc.of(sbDesc, paramDesc));
            cob.pop();
        } else {
            // Stack ya tiene [sb, valor]
            cob.invokestatic(ClassDesc.of(JsonSerializer.class.getName()), "staticAppendValue", 
                MethodTypeDesc.of(ConstantDescs.CD_void, sbDesc, ConstantDescs.CD_Object));
        }
    }

    private static ClassDesc toClassDesc(Class<?> type) {
        return ClassDesc.ofDescriptor(type.descriptorString());
    }
}
