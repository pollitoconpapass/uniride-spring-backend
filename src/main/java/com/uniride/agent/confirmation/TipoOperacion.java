package com.uniride.agent.confirmation;

public enum TipoOperacion {
    CREAR_RUTA, CREAR_VIAJE, SOLICITAR_UNIRSE, REGISTRAR_METODO,
    ACTUALIZAR_METODO, CAMBIAR_ESTADO_METODO, ELIMINAR_METODO,
    EDITAR_RUTA, ELIMINAR_RUTA, REPROGRAMAR_VIAJE, CANCELAR_SOLICITUD;

    boolean modificaMetodoExistente() {
        return this == ACTUALIZAR_METODO || this == CAMBIAR_ESTADO_METODO || this == ELIMINAR_METODO;
    }
}
