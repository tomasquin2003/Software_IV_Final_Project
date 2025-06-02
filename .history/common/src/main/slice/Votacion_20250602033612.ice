// Sistema de votación con patrón Reliable Messaging
#pragma once

module VotacionSystem {
    // Estructuras de datos
    struct Voto {
        string votoId;           // Identificador único del voto
        string candidatoId;      // Candidato elegido
        string estacionOrigen;   // Identificador de la estación de votación
        string timestamp;        // Marca de tiempo para auditoría
    };

    // Estados posibles de un voto durante el proceso
    enum EstadoVoto { PENDIENTE, RECIBIDO, PROCESADO, ERROR };

    // Excepciones
    exception VotoDuplicadoException {
        string votoId;
        string mensaje;
    };

    exception ErrorPersistenciaException {
        string mensaje;
    };

    exception VotanteNoAutorizadoException {
        string cedula;
        string mensaje;
    };

    // Definición de secuencia de votos (array de votos)
    sequence<Voto> VotoSeq;

    //-------------------- ESTACION VOTACION --------------------

    // Interfaz VotacionUI (UI de la estación)
    interface VotacionUI {
        string enviarVoto(string candidatoId);
    };

    // Interfaz ControllerEstacion
    interface ControllerEstacion {
        bool autenticarVotante(string cedula) throws VotanteNoAutorizadoException;
    };

    // Interfaz SistemaMonitorizacion
    interface SistemaMonitorizacion {
        bool iniciarVerificacion(string cedula) throws VotanteNoAutorizadoException;
    };

    // Interfaz VerificadorAsignacion
    interface VerificadorAsignacion {
        bool validarMesa(string cedula) throws VotanteNoAutorizadoException;
    };

    // Interfaz AlmacenamientoTransitorio
    interface AlmacenamientoTransitorio {
        void almacenarVotoTransitorio(string votoId, string candidatoId, EstadoVoto estado) 
            throws ErrorPersistenciaException;
        VotoSeq recuperarVotosPendientes() 
            throws ErrorPersistenciaException;
        void marcarVotoConfirmado(string votoId) 
            throws ErrorPersistenciaException;
    };    // Interfaz GestorEnvioVotosCallback (para recibir confirmaciones)
    interface GestorEnvioVotosCallback {
        void confirmarRecepcionVoto(string votoId, EstadoVoto estado);
    };

    // Interfaz GestorEnvioVotos
    interface GestorEnvioVotos {
        string enviarVoto(string candidatoId);
    };

    //-------------------- CENTRO VOTACION --------------------

    // Interfaz GestorRecepcionVotos
    interface GestorRecepcionVotos {
        // Método para recibir votos desde la estación
        void recibirVoto(Voto voto, GestorEnvioVotosCallback* callback) 
            throws VotoDuplicadoException, ErrorPersistenciaException;
        
        // Método para confirmar recepción (usado internamente)
        void confirmarRecepcionVoto(string votoId, EstadoVoto estado);
    };

    // Interfaz AlmacenamientoVotos
    interface AlmacenamientoVotos {
        void registrarVotoRecibido(string votoId, string candidatoId, EstadoVoto estado) 
            throws ErrorPersistenciaException;
        void marcarVotoProcesado(string votoId) 
            throws ErrorPersistenciaException;
        VotoSeq recuperarVotosPendientes() 
            throws ErrorPersistenciaException;
    };

    // Interfaz ValidadorDeVotos
    interface ValidadorDeVotos {
        bool validarVotoUnico(string votoId);
    };

    // Interfaz MotorEmisionVotos
    interface MotorEmisionVotos {
        void procesarVotoValidado(string candidatoId);
    };
};