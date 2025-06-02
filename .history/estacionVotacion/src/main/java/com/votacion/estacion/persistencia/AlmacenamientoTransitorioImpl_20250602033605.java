package com.votacion.estacion.persistencia;

import VotacionSystem.AlmacenamientoTransitorioDisp;
import VotacionSystem.EstadoVoto;
import VotacionSystem.ErrorPersistenciaException;
import VotacionSystem.Voto;
import VotacionSystem.VotoSeq;

import com.zeroc.Ice.Current;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Implementación del almacenamiento transitorio de votos.
 * Fecha: 2025-06-02
 * @author JRuiz1601
 */
public class AlmacenamientoTransitorioImpl extends _AlmacenamientoTransitorioDisp {

    private final String rutaArchivo = "estacionVotacion/src/main/resources/data/VotosTransitorios.csv";
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final String estacionId = "MESA-01";
    
    /**
     * Constructor del almacenamiento transitorio.
     * Asegura que el archivo CSV existe.
     */
    public AlmacenamientoTransitorioImpl() {
        try {
            File file = new File(rutaArchivo);
            if (!file.exists()) {
                // Crear archivo con encabezado si no existe
                try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
                    writer.println("votoId,candidatoId,timestamp,estado,intentos");
                }
                System.out.println("Archivo de votos transitorios creado: " + rutaArchivo);
            }
        } catch (IOException e) {
            System.err.println("Error al inicializar almacenamiento transitorio: " + e.getMessage());
        }
    }
    
    /**
     * Almacena un voto en estado transitorio en el archivo CSV.
     * @param votoId ID del voto
     * @param candidatoId ID del candidato
     * @param estado Estado del voto
     * @throws ErrorPersistenciaException si hay un error de persistencia
     */
    @Override
    public void almacenarVotoTransitorio(String votoId, String candidatoId, EstadoVoto estado, Current current) 
            throws ErrorPersistenciaException {
        
        lock.writeLock().lock();
        try {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String estadoStr = estado.toString();
            int intentos = 0;
            
            // Leer archivo actual
            List<String> lineas = Files.readAllLines(Paths.get(rutaArchivo));
            
            // Verificar si el voto ya existe
            boolean votoExistente = false;
            for (int i = 1; i < lineas.size(); i++) {
                String[] parts = lineas.get(i).split(",");
                if (parts.length >= 1 && parts[0].equals(votoId)) {
                    votoExistente = true;
                    lineas.set(i, votoId + "," + candidatoId + "," + timestamp + "," + estadoStr + "," + intentos);
                    break;
                }
            }
            
            // Si no existe, agregarlo
            if (!votoExistente) {
                lineas.add(votoId + "," + candidatoId + "," + timestamp + "," + estadoStr + "," + intentos);
            }
            
            // Escribir archivo actualizado
            Files.write(Paths.get(rutaArchivo), lineas);
            
            System.out.println("Voto almacenado transitoriamente: " + votoId);
        } catch (IOException e) {
            System.err.println("Error al almacenar voto transitorio: " + e.getMessage());
            throw new ErrorPersistenciaException("Error al almacenar voto transitorio: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Recupera los votos pendientes de confirmación.
     * @return Lista de votos pendientes
     * @throws ErrorPersistenciaException si hay un error de persistencia
     */
    @Override
    public VotoSeq recuperarVotosPendientes(Current current) throws ErrorPersistenciaException {
        lock.readLock().lock();
        try {
            List<Voto> votosPendientes = new ArrayList<>();
            
            List<String> lineas = Files.readAllLines(Paths.get(rutaArchivo));
            
            // Comenzar desde 1 para omitir el encabezado
            for (int i = 1; i < lineas.size(); i++) {
                String[] parts = lineas.get(i).split(",");
                
                if (parts.length >= 4) {
                    String estado = parts[3];
                    
                    // Solo incluir votos pendientes
                    if (estado.equals(EstadoVoto.PENDIENTE.toString())) {
                        Voto voto = new Voto();
                        voto.votoId = parts[0];
                        voto.candidatoId = parts[1];
                        voto.timestamp = parts[2];
                        voto.estacionOrigen = estacionId;
                        
                        votosPendientes.add(voto);
                    }
                }
            }
            
            // Convertir lista a array para VotoSeq
            Voto[] votosArray = votosPendientes.toArray(new Voto[0]);
            return new VotoSeq(votosArray);
            
        } catch (IOException e) {
            System.err.println("Error al recuperar votos pendientes: " + e.getMessage());
            throw new ErrorPersistenciaException("Error al recuperar votos pendientes: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Marca un voto como confirmado en el archivo CSV.
     * @param votoId ID del voto
     * @throws ErrorPersistenciaException si hay un error de persistencia
     */
    @Override
    public void marcarVotoConfirmado(String votoId, Current current) throws ErrorPersistenciaException {
        lock.writeLock().lock();
        try {
            List<String> lineas = Files.readAllLines(Paths.get(rutaArchivo));
            boolean votoEncontrado = false;
            
            // Comenzar desde 1 para omitir el encabezado
            for (int i = 1; i < lineas.size(); i++) {
                String[] parts = lineas.get(i).split(",");
                
                if (parts.length >= 4 && parts[0].equals(votoId)) {
                    // Actualizar estado a PROCESADO, mantener otros campos
                    String nuevoRegistro = parts[0] + "," + parts[1] + "," + parts[2] + "," 
                            + EstadoVoto.PROCESADO.toString() + "," + parts[4];
                    lineas.set(i, nuevoRegistro);
                    votoEncontrado = true;
                    break;
                }
            }
            
            if (!votoEncontrado) {
                System.err.println("Voto no encontrado para marcar como confirmado: " + votoId);
                throw new ErrorPersistenciaException("Voto no encontrado: " + votoId);
            }
            
            // Escribir archivo actualizado
            Files.write(Paths.get(rutaArchivo), lineas);
            
            System.out.println("Voto marcado como confirmado: " + votoId);
        } catch (IOException e) {
            System.err.println("Error al marcar voto como confirmado: " + e.getMessage());
            throw new ErrorPersistenciaException("Error al marcar voto como confirmado: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }
}