package com.registraduria.votacion.broker.model;

import Votacion.EstadoVoto;

/**
 * DatosBroker - Modelo de datos para almacenar información de votos en el VotosBroker.
 * 
 * Contiene toda la información necesaria para la transmisión confiable de votos.
 */
public class DatosBroker {
    
    public final String votoId;
    public final String datos;
    public final String timestamp;
    public final EstadoVoto estado;
    
    /**
     * Constructor de DatosBroker.
     * 
     * @param votoId ID único del voto
     * @param datos Datos del voto (en formato JSON)
     * @param timestamp Marca de tiempo
     * @param estado Estado actual del voto
     */
    public DatosBroker(String votoId, String datos, String timestamp, EstadoVoto estado) {
        this.votoId = votoId;
        this.datos = datos;
        this.timestamp = timestamp;
        this.estado = estado;
    }
    
    @Override
    public String toString() {
        return String.format("DatosBroker{votoId='%s', estado=%s, timestamp='%s'}", 
            votoId, estado, timestamp);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        DatosBroker that = (DatosBroker) obj;
        return votoId != null ? votoId.equals(that.votoId) : that.votoId == null;
    }
    
    @Override
    public int hashCode() {
        return votoId != null ? votoId.hashCode() : 0;
    }
} 