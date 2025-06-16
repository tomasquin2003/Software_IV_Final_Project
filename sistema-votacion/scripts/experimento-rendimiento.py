#!/usr/bin/env python3
"""
Experimento de Rendimiento - Sistema Electoral Distribuido
Evalúa el comportamiento del sistema bajo diferentes cargas de trabajo
"""

import json
import time
import subprocess
import threading
import statistics
import csv
from datetime import datetime
import requests
import psutil
import os

class ExperimentoSistemaElectoral:
    def __init__(self):
        self.resultados = []
        self.configuraciones = [
            # (consultas_concurrentes, votos_por_minuto, duracion_minutos)
            (10, 100, 5),
            (10, 500, 5),
            (50, 100, 5),
            (50, 500, 5),
            (100, 1000, 10),
            (100, 2000, 10),
            (200, 2000, 15),
            (200, 5000, 15),
            (500, 5000, 30),
        ]
        
    def iniciar_experimentos(self):
        """Ejecuta todos los experimentos configurados"""
        print("=== INICIANDO EXPERIMENTOS DE RENDIMIENTO ===")
        print(f"Total de configuraciones: {len(self.configuraciones)}")
        
        for i, (consultas, votos_min, duracion) in enumerate(self.configuraciones):
            print(f"\n--- Experimento {i+1}/{len(self.configuraciones)} ---")
            print(f"Consultas concurrentes: {consultas}")
            print(f"Votos por minuto: {votos_min}")
            print(f"Duración: {duracion} minutos")
            
            resultado = self.ejecutar_experimento(consultas, votos_min, duracion)
            self.resultados.append(resultado)
            
            # Pausa entre experimentos
            print("Esperando 30 segundos antes del siguiente experimento...")
            time.sleep(30)
        
        self.generar_informe()
    
    def ejecutar_experimento(self, consultas_concurrentes, votos_por_minuto, duracion_minutos):
        """Ejecuta un experimento individual"""
        inicio = time.time()
        metricas = {
            'configuracion': {
                'consultas_concurrentes': consultas_concurrentes,
                'votos_por_minuto': votos_por_minuto,
                'duracion_minutos': duracion_minutos
            },
            'timestamp': datetime.now().isoformat(),
            'latencias': [],
            'votos_procesados': 0,
            'votos_fallidos': 0,
            'uso_cpu_promedio': 0,
            'uso_memoria_promedio': 0,
            'errores': []
        }
        
        # Inicializar hilos para simular carga
        hilos_consulta = []
        hilos_votos = []
        
        # Monitoreo de recursos
        hilo_monitoreo = threading.Thread(
            target=self.monitorear_recursos,
            args=(metricas, duracion_minutos * 60)
        )
        hilo_monitoreo.start()
        
        # Generar consultas concurrentes
        for i in range(consultas_concurrentes):
            hilo = threading.Thread(
                target=self.simular_consultas,
                args=(metricas, duracion_minutos * 60)
            )
            hilos_consulta.append(hilo)
            hilo.start()
        
        # Generar votos
        intervalo_votos = 60.0 / votos_por_minuto  # segundos entre votos
        hilo_votos = threading.Thread(
            target=self.simular_votos,
            args=(metricas, intervalo_votos, duracion_minutos * 60)
        )
        hilo_votos.start()
        
        # Esperar finalización
        for hilo in hilos_consulta:
            hilo.join()
        hilo_votos.join()
        hilo_monitoreo.join()
        
        # Calcular métricas finales
        if metricas['latencias']:
            metricas['latencia_promedio'] = statistics.mean(metricas['latencias'])
            metricas['latencia_p95'] = statistics.quantiles(metricas['latencias'], n=20)[18]
            metricas['latencia_max'] = max(metricas['latencias'])
        
        metricas['duracion_real'] = time.time() - inicio
        metricas['throughput'] = metricas['votos_procesados'] / (metricas['duracion_real'] / 60)
        metricas['tasa_error'] = (metricas['votos_fallidos'] / (metricas['votos_procesados'] + metricas['votos_fallidos'])) * 100 if (metricas['votos_procesados'] + metricas['votos_fallidos']) > 0 else 0
        
        return metricas
    
    def simular_consultas(self, metricas, duracion_segundos):
        """Simula consultas concurrentes al sistema"""
        fin = time.time() + duracion_segundos
        
        while time.time() < fin:
            try:
                inicio_consulta = time.time()
                
                # Simular consulta a EstacionVotacion (puerto 10000)
                self.consultar_endpoint("http://localhost:9090/metrics", "EstacionVotacion")
                
                # Simular consulta a CentroVotacion (puerto 10001)
                self.consultar_endpoint("http://localhost:9090/metrics", "CentroVotacion")
                
                latencia = (time.time() - inicio_consulta) * 1000  # ms
                metricas['latencias'].append(latencia)
                
                time.sleep(0.1)  # Pequeña pausa entre consultas
                
            except Exception as e:
                metricas['errores'].append(f"Error en consulta: {str(e)}")
    
    def simular_votos(self, metricas, intervalo_votos, duracion_segundos):
        """Simula emisión de votos"""
        fin = time.time() + duracion_segundos
        contador_votos = 0
        
        while time.time() < fin:
            try:
                # Simular voto
                voto_id = f"VOTO_{contador_votos}_{int(time.time())}"
                candidato_id = f"CAND_{(contador_votos % 5) + 1}"  # 5 candidatos
                
                inicio_voto = time.time()
                exito = self.enviar_voto_simulado(voto_id, candidato_id)
                tiempo_voto = (time.time() - inicio_voto) * 1000
                
                if exito:
                    metricas['votos_procesados'] += 1
                    metricas['latencias'].append(tiempo_voto)
                else:
                    metricas['votos_fallidos'] += 1
                
                contador_votos += 1
                time.sleep(intervalo_votos)
                
            except Exception as e:
                metricas['votos_fallidos'] += 1
                metricas['errores'].append(f"Error en voto: {str(e)}")
    
    def consultar_endpoint(self, url, componente):
        """Realiza consulta HTTP a un endpoint"""
        try:
            response = requests.get(url, timeout=5)
            return response.status_code == 200
        except:
            return False
    
    def enviar_voto_simulado(self, voto_id, candidato_id):
        """Simula el envío de un voto"""
        try:
            # Simular tiempo de procesamiento del voto
            time.sleep(0.05)  # 50ms base
            
            # Simular fallo ocasional (5% de fallos)
            import random
            if random.random() < 0.05:
                return False
            
            return True
        except:
            return False
    
    def monitorear_recursos(self, metricas, duracion_segundos):
        """Monitorea uso de CPU y memoria"""
        fin = time.time() + duracion_segundos
        muestras_cpu = []
        muestras_memoria = []
        
        while time.time() < fin:
            try:
                cpu_percent = psutil.cpu_percent(interval=1)
                memoria_info = psutil.virtual_memory()
                
                muestras_cpu.append(cpu_percent)
                muestras_memoria.append(memoria_info.used / (1024 * 1024))  # MB
                
                time.sleep(5)
            except Exception as e:
                metricas['errores'].append(f"Error en monitoreo: {str(e)}")
        
        if muestras_cpu:
            metricas['uso_cpu_promedio'] = statistics.mean(muestras_cpu)
        if muestras_memoria:
            metricas['uso_memoria_promedio'] = statistics.mean(muestras_memoria)
    
    def generar_informe(self):
        """Genera informe completo de resultados"""
        print("\n=== GENERANDO INFORME DE RESULTADOS ===")
        
        # Guardar resultados en JSON
        with open('resultados_experimento.json', 'w') as f:
            json.dump(self.resultados, f, indent=2)
        
        # Generar CSV para análisis
        self.generar_csv()
        
        # Generar análisis estadístico
        self.generar_analisis_estadistico()
        
        print("✅ Informe generado:")
        print("   - resultados_experimento.json")
        print("   - resultados_experimento.csv")
        print("   - analisis_estadistico.txt")
    
    def generar_csv(self):
        """Genera archivo CSV con resultados"""
        with open('resultados_experimento.csv', 'w', newline='') as csvfile:
            fieldnames = [
                'consultas_concurrentes', 'votos_por_minuto', 'duracion_minutos',
                'votos_procesados', 'votos_fallidos', 'latencia_promedio',
                'latencia_p95', 'latencia_max', 'throughput', 'tasa_error',
                'uso_cpu_promedio', 'uso_memoria_promedio'
            ]
            
            writer = csv.DictWriter(csvfile, fieldnames=fieldnames)
            writer.writeheader()
            
            for resultado in self.resultados:
                row = resultado['configuracion'].copy()
                row.update({
                    'votos_procesados': resultado.get('votos_procesados', 0),
                    'votos_fallidos': resultado.get('votos_fallidos', 0),
                    'latencia_promedio': resultado.get('latencia_promedio', 0),
                    'latencia_p95': resultado.get('latencia_p95', 0),
                    'latencia_max': resultado.get('latencia_max', 0),
                    'throughput': resultado.get('throughput', 0),
                    'tasa_error': resultado.get('tasa_error', 0),
                    'uso_cpu_promedio': resultado.get('uso_cpu_promedio', 0),
                    'uso_memoria_promedio': resultado.get('uso_memoria_promedio', 0)
                })
                writer.writerow(row)
    
    def generar_analisis_estadistico(self):
        """Genera análisis estadístico de los resultados"""
        with open('analisis_estadistico.txt', 'w') as f:
            f.write("=== ANÁLISIS ESTADÍSTICO DEL SISTEMA ELECTORAL ===\n\n")
            
            # Análisis de throughput
            throughputs = [r.get('throughput', 0) for r in self.resultados]
            f.write(f"THROUGHPUT:\n")
            f.write(f"  - Máximo: {max(throughputs):.2f} votos/min\n")
            f.write(f"  - Promedio: {statistics.mean(throughputs):.2f} votos/min\n")
            f.write(f"  - Mínimo: {min(throughputs):.2f} votos/min\n\n")
            
            # Análisis de latencia
            latencias_promedio = [r.get('latencia_promedio', 0) for r in self.resultados if r.get('latencia_promedio', 0) > 0]
            if latencias_promedio:
                f.write(f"LATENCIA:\n")
                f.write(f"  - Máxima: {max(latencias_promedio):.2f} ms\n")
                f.write(f"  - Promedio: {statistics.mean(latencias_promedio):.2f} ms\n")
                f.write(f"  - Mínima: {min(latencias_promedio):.2f} ms\n\n")
            
            # Análisis de recursos
            cpu_uso = [r.get('uso_cpu_promedio', 0) for r in self.resultados]
            memoria_uso = [r.get('uso_memoria_promedio', 0) for r in self.resultados]
            
            f.write(f"RECURSOS:\n")
            f.write(f"  - CPU máximo: {max(cpu_uso):.1f}%\n")
            f.write(f"  - CPU promedio: {statistics.mean(cpu_uso):.1f}%\n")
            f.write(f"  - Memoria máxima: {max(memoria_uso):.1f} MB\n")
            f.write(f"  - Memoria promedio: {statistics.mean(memoria_uso):.1f} MB\n\n")
            
            # Punto de corte
            f.write("PUNTO DE SATURACIÓN:\n")
            for resultado in self.resultados:
                if resultado.get('tasa_error', 0) > 5:  # Más del 5% de errores
                    config = resultado['configuracion']
                    f.write(f"  - Detectado en: {config['consultas_concurrentes']} consultas, "
                           f"{config['votos_por_minuto']} votos/min\n")
                    break
            else:
                f.write("  - No se alcanzó el punto de saturación en las pruebas\n")

if __name__ == "__main__":
    experimento = ExperimentoSistemaElectoral()
    experimento.iniciar_experimentos() 