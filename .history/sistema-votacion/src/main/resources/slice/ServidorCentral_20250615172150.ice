#pragma once

#include "Common.ice"

module Votacion {
    // Estructuras de datos específicas del ServidorCentral
    struct VotoAnonimo {
        string votoId;
        string candidatoId;
        string timestamp;
        string hash;
    };

    struct ConfiguracionMesa {
        string mesaId;
        string ubicacion;
        int capacidadMaxima;
        string responsable;
        EstadoMesa estado;
    };

    struct AsignacionMesa {
        string cedula;
        string mesaId;
        string timestamp;
        bool confirmada;
    };

    struct CandidatoData {
        string candidatoId;
        string nombre;
        string partido;
        string cargo;
        bool activo;
    };

    struct DistribucionMesa {
        string mesaId;
        int votantesAsignados;
        int capacidadTotal;
        double porcentajeOcupacion;
    };

    // Enums para ServidorCentral
    enum EstadoMesa { CONFIGURADA, ACTIVA, CERRADA, SUSPENDIDA };
    enum EstadoImportacion { PENDIENTE, EN_PROCESO, COMPLETADA, ERROR };
    enum TipoMesa { ORDINARIA, ESPECIAL, CARCEL, HOSPITAL };

    // Excepciones específicas del ServidorCentral
    exception CandidatoDuplicadoException {
        string candidatoId;
        string mensaje;
    };

    exception MesaNoExisteException {
        string mesaId;
        string mensaje;
    };

    exception ImportacionException {
        string archivo;
        string mensaje;
    };

    // Interfaces del ServidorCentral

    interface ControllerCentral {
        // Provee
        void recibirVotoDesdeEstacion(string votoId, string candidatoId, string estacionId, string hash)
            throws ErrorPersistenciaException, VotoDuplicadoException;
        void confirmarRecepcionCentral(string votoId, EstadoVoto estado)
            throws ErrorPersistenciaException;
        
        // Requiere
        void registrarVotoAnonimo(string votoId, string candidatoId, string timestamp, string hash)
            throws ErrorPersistenciaException;
        void confirmarPersistenciaVoto(string votoId, EstadoVoto estado)
            throws ErrorPersistenciaException;
    };

    interface RegistroVotos {
        // Provee
        void registrarVotoAnonimo(string votoId, string candidatoId, string timestamp, string hash)
            throws ErrorPersistenciaException;
        void confirmarPersistenciaVoto(string votoId, EstadoVoto estado)
            throws ErrorPersistenciaException;
        
        // Requiere
        void guardarVoto(string votoId, string candidatoId, string timestamp, string hash)
            throws ErrorPersistenciaException;
        EstadoVoto verificarEstadoVoto(string votoId)
            throws ErrorPersistenciaException;
    };

    interface GestorCandidatos {
        // Provee
        bool validarCandidatosUnicos()
            throws CandidatoDuplicadoException, ErrorPersistenciaException;
        void gestionarCandidatos()
            throws ErrorPersistenciaException;
        EstadoImportacion importacionCandidatosExcel()
            throws ImportacionException, ErrorPersistenciaException;
        
        // Requiere
        void guardarCandidatos()
            throws ErrorPersistenciaException;
    };

    interface AdministradorMesa {
        // Provee
        string consultarAsignacionMesas()
            throws MesaNoExisteException, ErrorPersistenciaException;
        void configurarMesas()
            throws ErrorPersistenciaException;
        void asignarVotantesMesa()
            throws MesaNoExisteException, ErrorPersistenciaException;
        string visualizarDistribucion()
            throws ErrorPersistenciaException;
    };

    interface InterfazAdministrativa {
        // Requiere
        void configurarMesas()
            throws ErrorPersistenciaException;
        void asignarVotantesMesa()
            throws MesaNoExisteException, ErrorPersistenciaException;
        string visualizarDistribucion()
            throws ErrorPersistenciaException;
        bool validarCandidatosUnicos()
            throws CandidatoDuplicadoException, ErrorPersistenciaException;
        void gestionarCandidatos()
            throws ErrorPersistenciaException;
        EstadoImportacion importacionCandidatosExcel()
            throws ImportacionException, ErrorPersistenciaException;
    };

    interface GestorConsultasRegional {
        // Interface para consultas regionales
        string consultarAsignacionMesas()
            throws MesaNoExisteException, ErrorPersistenciaException;
    };

    interface ConnectionManager {
        // Interface para conexión remota con base de datos
        void guardarVoto(string votoId, string candidatoId, string timestamp, string hash)
            throws ErrorPersistenciaException;
        EstadoVoto verificarEstadoVoto(string votoId)
            throws ErrorPersistenciaException;
        void guardarCandidatos()
            throws ErrorPersistenciaException;
    };
};
