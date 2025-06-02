package com.votacion.estacion.votacion;

import VotacionSystem.GestorEnvioVotosCallback;
import VotacionSystem.EstadoVoto;
import com.zeroc.Ice.Current;

/**
 * Implementación del callback para el gestor de envío de votos.
 * Fecha: 2025-06-02
 * @author JRuiz1601
 */
public class GestorEnvioVotosCallbackImpl implements GestorEnvioVotosCallback {
    
    private final GestorEnvioVotosImpl gestorEnvioVotos;
    private final String votoId;
    
    public GestorEnvioVotosCallbackImpl(GestorEnvioVotosImpl gestorEnvioVotos, String votoId) {
        this.gestorEnvioVotos = gestorEnvioVotos;
        this.votoId = votoId;
    }
    
    @Override
    public void confirmarRecepcionVoto(String votoId, EstadoVoto estado, Current current) {
        System.out.println("Confirmación recibida para voto " + votoId + " con estado: " + estado);
        gestorEnvioVotos.procesarConfirmacionVoto(votoId, estado);
    }
}
