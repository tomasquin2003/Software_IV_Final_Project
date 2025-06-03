// src/main/resources/slice/CentroVotacion.ice
#pragma once

#include "Common.ice"
#include "EstacionVotacion.ice"

module Votacion {
    // Interfaces para el Centro de Votación
    
    interface GestorRecepcionVotos {
        // Método remoto para recibir votos desde GestorEnvioVotos
        // Usando GestorEnvioVotosCallback* para indicar que es un proxy
        void recibirVoto(Voto voto, GestorEnvioVotosCallback* callback) 
            throws VotoDuplicadoException, ErrorPersistenciaException;
    };

    interface AlmacenamientoVotos {
        void registrarVotoRecibido(string votoId, string candidatoId, EstadoVoto estado)
            throws ErrorPersistenciaException;
        void marcarVotoProcesado(string votoId)
            throws ErrorPersistenciaException;
        Voto[] recuperarVotosPendientes()
            throws ErrorPersistenciaException;
    };

    interface ValidadorDeVotos {
        // Método requerido por GestorRecepcionVotos
        bool validarVotoUnico(string votoId);
    };

    interface MotorEmisionVotos {
        // Método que consume procesarVotoValidado
        void procesarVotoValidado(string candidatoId);
    };
};