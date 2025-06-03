#pragma once

module Votacion {
    // Estructuras de datos comunes
    struct Voto {
        string votoId;
        string candidatoId;
        string estacionOrigen;
        string timestamp;
    };
    
    enum EstadoVoto { PENDIENTE, RECIBIDO, PROCESADO, ERROR };

    // Excepciones del sistema
    exception ErrorPersistenciaException {
        string mensaje;
    };

    exception VotoDuplicadoException {
        string votoId;
        string mensaje;
    };

    exception VotanteNoAutorizadoException {
        string cedula;
        string mensaje;
    };

    // Interfaces comunes usadas por ambos sistemas
    class GestorEnvioVotosCallback {
        void confirmarRecepcionVoto(string votoId, EstadoVoto estado);
    };
};