package org.emi.json;

/**
 * Interfaz funcional para la instanciación de Records de forma optimizada.
 * El código generado por Class-File API implementará esta interfaz para
 * evitar el uso de MethodHandles genéricos y boxing en el hot path.
 */
@FunctionalInterface
public interface RecordInstantiator<T> {
    /**
     * Crea una nueva instancia del Record usando el array de argumentos.
     * La implementación generada realizará los casteos y unboxing necesarios
     * antes de llamar al constructor canónico.
     */
    T instantiate(Object[] args);
}
