package org.emi.json;

/**
 * Interfaz funcional para la instanciación optimizada de Records.
 * Implementada dinámicamente vía bytecode para evitar reflexión.
 * 
 * <p>Ejemplo:</p>
 * <pre>{@code
 * Object[] args = { "Emi", 25 };
 * User u = instantiator.instantiate(args);
 * }</pre>
 */
@FunctionalInterface
public interface RecordInstantiator<T> {
    /**
     * Crea una nueva instancia del Record.
     * @param args Argumentos para el constructor canónico.
     * @return Nueva instancia del Record.
     */
    T instantiate(Object[] args);
}
