#pragma once

#include "Common.ice"

module Votacion {
    // Estructuras de datos específicas del VotosBroker
    struct VotoPendiente {
        string votoId;
        string candidatoId;
        string estacionId;
        string hash;
        string timestamp;
        int prioridad;
        int intentosEnvio;
    };

    struct DatosBroker {
        string votoId;
        string datos;
        string timestamp;
        EstadoVoto estado;
    };

    // Enums para VotosBroker
    enum EstadoCircuito { CERRADO, ABIERTO, MEDIO_ABIERTO };
    enum PrioridadVoto { BAJA, NORMAL, ALTA, CRITICA };

    // Excepciones específicas del VotosBroker
    exception CircuitBreakerException {
        string destino;
        string mensaje;
    };

    exception QueueFullException {
        string mensaje;
    };

    // Interfaces del VotosBroker

    interface BrokerManager {
        // Provee
        void transmitirVotoServidor(string votoId, string candidatoId, string hash)
            throws ErrorPersistenciaException, CircuitBreakerException;
        void confirmarEnvioServidor(string votoId, EstadoVoto estado)
            throws ErrorPersistenciaException;
        
        // Requiere
        void recibirVotoDesdeEstacion(string votoId, string candidatoId, string estacionId, string hash)
            throws ErrorPersistenciaException, VotoDuplicadoException;
        void confirmarRecepcionCentral(string votoId, EstadoVoto estado)
            throws ErrorPersistenciaException;
    };

    interface QueueService {
        // Provee
        void encolarVoto(string votoId, string candidatoId, PrioridadVoto prioridad)
            throws QueueFullException, ErrorPersistenciaException;
        VotoPendiente recuperarSiguienteVoto()
            throws ErrorPersistenciaException;
    };

    interface PersistenceManager {
        // Provee
        void almacenarVotoPendiente(string votoId, DatosBroker datos)
            throws ErrorPersistenciaException;
        string recuperarVotosPendientesEnvio()
            throws ErrorPersistenciaException;
        void marcarVotoEnviado(string votoId)
            throws ErrorPersistenciaException;
    };

    interface CircuitBreakerService {
        // Provee
        bool verificarCircuitoAbierto(string destino)
            throws ErrorPersistenciaException;
        void registrarFallo(string destino)
            throws ErrorPersistenciaException;
        void registrarExito(string destino)
            throws ErrorPersistenciaException;
    };

    interface RetryHandler {
        // Interface para manejo de reintentos
        void procesarVotosPendientes()
            throws ErrorPersistenciaException, CircuitBreakerException;
    };
};
