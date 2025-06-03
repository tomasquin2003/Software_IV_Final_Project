// src/main/resources/slice/EstacionVotacion.ice
#pragma once

#include "Common.ice"

module Votacion {
    // Interfaces para la Estación de Votación
    
    interface ControllerEstacion {
        // Método requerido por VotacionUI
        bool autenticarVotante(string cedula) throws VotanteNoAutorizadoException;
    };

    interface SistemaMonitorizacion {
        // Método requerido por ControllerEstacion
        bool iniciarVerificacion(string cedula) throws VotanteNoAutorizadoException;
    };

    interface VerificadorAsignacion {
        // Método requerido por SistemaMonitorizacion
        bool validarMesa(string cedula) throws VotanteNoAutorizadoException;
    };

    // Callback para recibir confirmaciones desde el centro
    interface GestorEnvioVotosCallback {
        void confirmarRecepcionVoto(string votoId, EstadoVoto estado);
    };

    interface AlmacenamientoTransitorio {
        void almacenarVotoTransitorio(string votoId, string candidatoId, EstadoVoto estado)
            throws ErrorPersistenciaException;
        Voto[] recuperarVotosPendientes()
            throws ErrorPersistenciaException;
        void marcarVotoConfirmado(string votoId)
            throws ErrorPersistenciaException;
    };

};