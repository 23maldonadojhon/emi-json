package org.emi.json;

import java.lang.classfile.*;
import java.lang.reflect.AccessFlag;
import java.lang.constant.*;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

/**
 * Generador de bytecode dinámico utilizando la Class-File API (Java 25).
 * Genera implementaciones de {@link RecordInstantiator} optimizadas para cada Record,
 * eliminando el overhead de reflexión al instanciar objetos.
 * 
 * <p>Ejemplo de uso interno:</p>
 * <pre>{@code
 * RecordInstantiator<User> inst = ParserGenerator.generateInstantiator(User.class);
 * User u = inst.instantiate(args);
 * }</pre>
 */
public final class ParserGenerator {

    private ParserGenerator() {}

    /**
     * Genera una implementación de {@link RecordInstantiator} en tiempo de ejecución.
     * La clase generada es una "Hidden Class" que reside en el mismo paquete que el Record
     * y tiene acceso privilegiado a su constructor canónico.
     * 
     * @param <T> El tipo del Record.
     * @param recordClass Clase del Record a instanciar.
     * @return Una instancia de la clase generada lista para crear Records.
     * 
     * <p>Ejemplo:</p>
     * <pre>{@code
     * RecordInstantiator<User> inst = ParserGenerator.generateInstantiator(User.class);
     * User u = inst.instantiate(new Object[] { 1, "Emi", true });
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public static <T> RecordInstantiator<T> generateInstantiator(Class<T> recordClass) {
        if (!recordClass.isRecord()) {
            throw new IllegalArgumentException(recordClass.getName() + " is not a record");
        }

        ClassFile cf = ClassFile.of();
        
        // Descriptores nominales
        ClassDesc recordDesc = ClassDesc.of(recordClass.getName());
        ClassDesc interfaceDesc = ClassDesc.of(RecordInstantiator.class.getName());
        
        // Nombre de la clase generada (clase oculta)
        ClassDesc generatedDesc = recordDesc.nested("Instantiator");

        byte[] classBytes = cf.build(generatedDesc, clb -> {
            clb.withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL);
            clb.withInterfaceSymbols(interfaceDesc);

            // 1. Constructor por defecto: public <init>() { super(); }
            clb.withMethod(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, AccessFlag.PUBLIC.mask(), mb -> {
                mb.withCode(cob -> {
                    cob.aload(0);
                    cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                    cob.return_();
                });
            });

            // 2. Método: public Object instantiate(Object[] args)
            clb.withMethod("instantiate", 
                MethodTypeDesc.of(ConstantDescs.CD_Object, ClassDesc.ofDescriptor("[Ljava/lang/Object;")), 
                AccessFlag.PUBLIC.mask(), mb -> {
                mb.withCode(cob -> {
                    // Cargar nueva instancia del Record
                    cob.new_(recordDesc);
                    cob.dup();

                    RecordComponent[] components = recordClass.getRecordComponents();
                    for (int i = 0; i < components.length; i++) {
                        // Leer args[i]
                        cob.aload(1); 
                        cob.ldc(i);
                        cob.aaload(); 

                        // Aplicar casting o unboxing
                        emitUnboxing(cob, components[i].getType());
                    }

                    // Invocar constructor canónico
                    var paramDescs = Arrays.stream(components)
                        .map(c -> toClassDesc(c.getType()))
                        .toList();
                    
                    cob.invokespecial(recordDesc, ConstantDescs.INIT_NAME, 
                        MethodTypeDesc.of(ConstantDescs.CD_void, paramDescs.toArray(new ClassDesc[0])));

                    // Devolver objeto creado
                    cob.areturn();
                });
            });
        });

        try {
            // Definir la clase como una Hidden Class vinculada al Record
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(recordClass, MethodHandles.lookup());
            Class<?> hiddenClass = lookup.defineHiddenClass(classBytes, true, MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
            return (RecordInstantiator<T>) hiddenClass.getConstructor().newInstance();
        } catch (Exception e) {
            throw new JsonMappingException("Falla crítica al generar bytecode para " + recordClass.getName(), e);
        }
    }

    private static ClassDesc toClassDesc(Class<?> type) {
        return ClassDesc.ofDescriptor(type.descriptorString());
    }

    /**
     * Emite instrucciones de unboxing o casting según el tipo destino.
     */
    private static void emitUnboxing(CodeBuilder cob, Class<?> type) {
        if (!type.isPrimitive()) {
            cob.checkcast(toClassDesc(type));
            return;
        }

        if (type == int.class) {
            cob.checkcast(ClassDesc.of(Integer.class.getName()));
            cob.invokevirtual(ClassDesc.of(Integer.class.getName()), "intValue", MethodTypeDesc.of(ConstantDescs.CD_int));
        } else if (type == long.class) {
            cob.checkcast(ClassDesc.of(Long.class.getName()));
            cob.invokevirtual(ClassDesc.of(Long.class.getName()), "longValue", MethodTypeDesc.of(ConstantDescs.CD_long));
        } else if (type == boolean.class) {
            cob.checkcast(ClassDesc.of(Boolean.class.getName()));
            cob.invokevirtual(ClassDesc.of(Boolean.class.getName()), "booleanValue", MethodTypeDesc.of(ConstantDescs.CD_boolean));
        } else if (type == double.class) {
            cob.checkcast(ClassDesc.of(Double.class.getName()));
            cob.invokevirtual(ClassDesc.of(Double.class.getName()), "doubleValue", MethodTypeDesc.of(ConstantDescs.CD_double));
        } else if (type == float.class) {
            cob.checkcast(ClassDesc.of(Float.class.getName()));
            cob.invokevirtual(ClassDesc.of(Float.class.getName()), "floatValue", MethodTypeDesc.of(ConstantDescs.CD_float));
        } else if (type == byte.class) {
            cob.checkcast(ClassDesc.of(Byte.class.getName()));
            cob.invokevirtual(ClassDesc.of(Byte.class.getName()), "byteValue", MethodTypeDesc.of(ConstantDescs.CD_byte));
        } else if (type == short.class) {
            cob.checkcast(ClassDesc.of(Short.class.getName()));
            cob.invokevirtual(ClassDesc.of(Short.class.getName()), "shortValue", MethodTypeDesc.of(ConstantDescs.CD_short));
        } else if (type == char.class) {
            cob.checkcast(ClassDesc.of(Character.class.getName()));
            cob.invokevirtual(ClassDesc.of(Character.class.getName()), "charValue", MethodTypeDesc.of(ConstantDescs.CD_char));
        }
    }
}
