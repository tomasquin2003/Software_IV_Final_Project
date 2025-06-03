#pragma once

#include "Common.ice"

module Votacion {
    interface AlmacenamientoTransitorio {
        void almacenarVotoTransitorio(string votoId, string candidatoId, EstadoVoto estado)
            throws ErrorPersistenciaException;
        // Cambiamos para evitar secuencias
        bool hayVotosPendientes()
            throws ErrorPersistenciaException;
        Voto obtenerVotoPendiente(string votoId)
            throws ErrorPersistenciaException;
        // Método para obtener los IDs de votos pendientes como string (separados por coma)
        string obtenerIdsVotosPendientes()
            throws ErrorPersistenciaException;
        void marcarVotoConfirmado(string votoId)
            throws ErrorPersistenciaException;
    };

    // Ya no definimos GestorEnvioVotosCallback aquí, se movió a Common.ice

    interface VerificadorAsignacion {
        bool validarMesa(string cedula)
            throws VotanteNoAutorizadoException;
    };

    interface SistemaMonitorizacion {
        bool iniciarVerificacion(string cedula)
            throws VotanteNoAutorizadoException;
    };

    interface ControllerEstacion {
        bool autenticarVotante(string cedula)
            throws VotanteNoAutorizadoException;
    };
};