package com.votacion.estacion.monitoreo;

import VotacionSystem.SistemaMonitorizacion;
import VotacionSystem.VerificadorAsignacion;
import VotacionSystem.VotanteNoAutorizadoException;

import com.zeroc.Ice.Current;

/**
 * Implementación del sistema de monitorización de la estación de votación.
 * Fecha: 2025-06-02
 * @author JRuiz1601
 */
public class SistemaMonitorizacionImpl implements SistemaMonitorizacion {

    private final VerificadorAsignacion verificadorAsignacion;
    
    /**
     * Constructor del sistema de monitorización.
     * @param verificadorAsignacion Verificador de asignación de mesa
     */
    public SistemaMonitorizacionImpl(VerificadorAsignacion verificadorAsignacion) {
        this.verificadorAsignacion = verificadorAsignacion;
    }
    
    /**
     * Inicia el proceso de verificación de un votante.
     * @param cedula Número de cédula del votante
     * @return true si el votante está autorizado
     * @throws VotanteNoAutorizadoException si el votante no está autorizado
     */
    @Override
    public boolean iniciarVerificacion(String cedula, Current current) throws VotanteNoAutorizadoException {
        System.out.println("Iniciando verificación para: " + cedula);
        
        try {
            return verificadorAsignacion.validarMesa(cedula);
        } catch (VotanteNoAutorizadoException e) {
            System.out.println("Error de verificación: " + e.mensaje);
            throw e;
        } catch (Exception e) {
            System.out.println("Error inesperado en verificación: " + e.getMessage());
            throw new VotanteNoAutorizadoException(cedula, "Error en el proceso de verificación");
        }
    }
}