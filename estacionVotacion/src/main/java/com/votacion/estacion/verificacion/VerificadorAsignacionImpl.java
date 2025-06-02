package com.votacion.estacion.verificacion;

import VotacionSystem.VerificadorAsignacionDisp;
import VotacionSystem.VotanteNoAutorizadoException;

import com.zeroc.Ice.Current;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Implementación del verificador de asignación de mesa.
 * Fecha: 2025-06-02
 * @author JRuiz1601
 */
public class VerificadorAsignacionImpl extends _VerificadorAsignacionDisp {

    private final Set<String> cedulasAutorizadas;
    private final String rutaArchivoCedulas = "estacionVotacion/src/main/resources/data/CedulasAutorizadas.csv";
    
    /**
     * Constructor del verificador de asignación.
     * Carga la lista de cédulas autorizadas desde el archivo CSV.
     */
    public VerificadorAsignacionImpl() {
        this.cedulasAutorizadas = new HashSet<>();
        cargarCedulasAutorizadas();
    }
    
    /**
     * Valida si un votante está asignado a esta mesa.
     * @param cedula Número de cédula del votante
     * @return true si el votante está autorizado
     * @throws VotanteNoAutorizadoException si el votante no está autorizado
     */
    @Override
    public boolean validarMesa(String cedula, Current current) throws VotanteNoAutorizadoException {
        System.out.println("Validando asignación de mesa para: " + cedula);
        
        if (cedulasAutorizadas.contains(cedula)) {
            System.out.println("Cédula " + cedula + " autorizada en esta mesa");
            return true;
        } else {
            System.out.println("Cédula " + cedula + " NO autorizada en esta mesa");
            throw new VotanteNoAutorizadoException("Votante no asignado a esta mesa", cedula);
        }
    }
    
    /**
     * Carga la lista de cédulas autorizadas desde el archivo CSV.
     */
    private void cargarCedulasAutorizadas() {
        try (BufferedReader br = new BufferedReader(new FileReader(rutaArchivoCedulas))) {
            String line;
            boolean isHeader = true;
            
            while ((line = br.readLine()) != null) {
                if (isHeader) {
                    isHeader = false;
                    continue;
                }
                
                String[] parts = line.split(",");
                if (parts.length >= 1) {
                    cedulasAutorizadas.add(parts[0]);
                }
            }
            
            System.out.println("Cédulas autorizadas cargadas: " + cedulasAutorizadas.size());
        } catch (IOException e) {
            System.err.println("Error al cargar cédulas autorizadas: " + e.getMessage());
            // Agregar algunas cédulas de respaldo para pruebas
            cedulasAutorizadas.add("1000123456");
            cedulasAutorizadas.add("1000234567");
            cedulasAutorizadas.add("1000345678");
        }
    }
}