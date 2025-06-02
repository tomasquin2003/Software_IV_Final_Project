package com.votacion.estacion.controller;

import VotacionSystem.ControllerEstacion;
import VotacionSystem.SistemaMonitorizacion;
import VotacionSystem.VotanteNoAutorizadoException;

import com.zeroc.Ice.Current;

/**
 * Implementación del controlador de la estación de votación.
 * Fecha: 2025-06-02
 * @author JRuiz1601
 */
public class ControllerEstacionImpl implements ControllerEstacion {

    private final SistemaMonitorizacion sistemaMonitorizacion;
    
    /**
     * Constructor del controlador.
     * @param sistemaMonitorizacion Sistema de monitorización
     */
    public ControllerEstacionImpl(SistemaMonitorizacion sistemaMonitorizacion) {
        this.sistemaMonitorizacion = sistemaMonitorizacion;
    }
    
    /**
     * Autentica a un votante verificando si está autorizado para votar en esta mesa.
     * @param cedula Número de cédula del votante
     * @return true si el votante está autorizado
     * @throws VotanteNoAutorizadoException si el votante no está autorizado
     */
    @Override
    public boolean autenticarVotante(String cedula, Current current) throws VotanteNoAutorizadoException {
        System.out.println("Iniciando autenticación del votante: " + cedula);
        
        try {
            boolean autorizado = sistemaMonitorizacion.iniciarVerificacion(cedula);
            
            if (autorizado) {
                System.out.println("Votante autorizado: " + cedula);
                return true;
            } else {
                System.out.println("Votante no autorizado: " + cedula);
                throw new VotanteNoAutorizadoException(cedula, "Votante no autorizado en esta mesa");
            }
        } catch (VotanteNoAutorizadoException e) {
            System.out.println("Error de autenticación: " + e.mensaje);
            throw e;
        } catch (Exception e) {
            System.out.println("Error inesperado en autenticación: " + e.getMessage());
            throw new VotanteNoAutorizadoException(cedula, "Error en el proceso de autenticación");
        }
    }
}