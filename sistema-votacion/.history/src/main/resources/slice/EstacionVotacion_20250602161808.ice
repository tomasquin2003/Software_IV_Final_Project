// src/main/resources/slice/EstacionVotacion.ice
#pragma once

#include "Common.ice"  // Cambiado de <Common.ice> a "Common.ice"

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

    interface AlmacenamientoTransitorio {
        // Métodos requeridos por GestorEnvioVotos
        void almacenarVotoTransitorio(string votoId, string candidatoId, EstadoVoto estado) 
            throws ErrorPersistenciaException;
        Voto[] recuperarVotosPendientes() 
            throws ErrorPersistenciaException;
        void marcarVotoConfirmado(string votoId) 
            throws ErrorPersistenciaException;
    };

    // Callback para recibir confirmaciones desde el centro
    interface GestorEnvioVotosCallback {
        void confirmarRecepcionVoto(string votoId, EstadoVoto estado);
    };

    // Nota: GestorEnvioVotos no se define como interfaz Slice porque no provee
    // servicios remotos, sino que consume servicios remotos a través del proxy
    // de GestorRecepcionVotos
};