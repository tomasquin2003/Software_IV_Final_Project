// Definición del sistema de votación
#pragma once

module VotacionSystem {
    // Estructura para representar un voto
    struct Voto {
        string idVoto;          // Identificador único del voto
        string idEstacion;      // Identificador de la estación de votación
        string candidato;       // Candidato elegido
        long timestamp;         // Marca de tiempo para auditoría
    };

    // Excepción para voto duplicado
    exception VotoDuplicadoException {
        string mensaje;
        string idVoto;
    };

    // Excepción para errores de transmisión
    exception ErrorTransmisionException {
        string mensaje;
    };

    // Interfaz para el Centro de Votación (servidor)
    interface CentroVotacionService {
        // Método para registrar un voto
        bool registrarVoto(Voto voto) throws VotoDuplicadoException, ErrorTransmisionException;
        
        // Método para verificar estado del sistema
        bool verificarEstado();
        
        // Método para obtener conteo actual (podría ser restringido)
        int obtenerConteoActual(string candidato);
    };

    // Interfaz para las notificaciones del centro a las estaciones (callbacks)
    interface EstacionVotacionCallback {
        // Notificación de recepción exitosa
        void notificarRecepcionVoto(string idVoto, bool exitoso, string mensaje);
        
        // Actualizaciones de estado del sistema
        void actualizarEstadoSistema(string estado);
    };

    // Interfaz para la Estación de Votación
    interface EstacionVotacionService {
        // Registrar callback para recibir notificaciones
        void registrarCallback(EstacionVotacionCallback* callback);
        
        // Método para verificar conexión
        bool ping();
    };
};