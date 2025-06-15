#pragma once

#include "Common.ice"

module Votacion {
    interface AlmacenamientoVotos {
        void registrarVotoRecibido(string votoId, string candidatoId, string estacionOrigen, EstadoVoto estado)
            throws ErrorPersistenciaException;
        void marcarVotoProcesado(string votoId)
            throws ErrorPersistenciaException;
        // Cambiamos para evitar secuencias
        bool hayVotosPendientes()
            throws ErrorPersistenciaException;
        Voto obtenerVotoPendiente(string votoId)
            throws ErrorPersistenciaException;
        // Método para obtener los IDs de votos pendientes como string (separados por coma)
        string obtenerIdsVotosPendientes()
            throws ErrorPersistenciaException;
    };

    interface ValidadorDeVotos {
        bool validarVotoUnico(string votoId);
    };

    interface MotorEmisionVotos {
        void procesarVotoValidado(string candidatoId);
    };    
    
    interface GestorRecepcionVotos {
        // Usar proxy (asterisco) para callback remoto
        void recibirVoto(Voto voto, GestorEnvioVotosCallback* callback)
            throws VotoDuplicadoException, ErrorPersistenciaException;
    };
};