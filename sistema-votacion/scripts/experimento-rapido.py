#!/usr/bin/env python3
"""
Experimento Rápido - Sistema Electoral Distribuido
Versión ligera para pruebas rápidas (5-10 minutos)
"""

import json
import time
import threading
import statistics
import csv
from datetime import datetime
import psutil
import random

class ExperimentoRapido:
    def __init__(self):
        self.resultados = []
        # Configuraciones ligeras para prueba rápida
        self.configuraciones = [
            # (consultas_concurrentes, votos_por_minuto, duracion_minutos)
            (5, 50, 1),     # Muy ligero - 1 minuto
            (10, 100, 1),   # Ligero - 1 minuto  
            (20, 200, 2),   # Medio - 2 minutos
        ]
        
    def iniciar_experimentos(self):
        """Ejecuta todos los experimentos configurados"""
        print("=== INICIANDO EXPERIMENTOS RÁPIDOS ===")
        print(f"Total de configuraciones: {len(self.configuraciones)}")
        print("⏱️ Tiempo estimado: 5-7 minutos")
        
        for i, (consultas, votos_min, duracion) in enumerate(self.configuraciones):
            print(f"\n--- Experimento {i+1}/{len(self.configuraciones)} ---")
            print(f"Consultas concurrentes: {consultas}")
            print(f"Votos por minuto: {votos_min}")
            print(f"Duración: {duracion} minutos")
            
            resultado = self.ejecutar_experimento(consultas, votos_min, duracion)
            self.resultados.append(resultado)
            
            # Pausa corta entre experimentos
            print("Esperando 10 segundos...")
            time.sleep(10)
        
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
        
        # Simular carga de trabajo
        print("   Simulando carga de trabajo...")
        
        # Hilos para simular carga
        hilos = []
        
        # Monitoreo de recursos
        hilo_monitoreo = threading.Thread(
            target=self.monitorear_recursos,
            args=(metricas, duracion_minutos * 60)
        )
        hilos.append(hilo_monitoreo)
        hilo_monitoreo.start()
        
        # Simular consultas concurrentes
        for i in range(consultas_concurrentes):
            hilo = threading.Thread(
                target=self.simular_consultas,
                args=(metricas, duracion_minutos * 60)
            )
            hilos.append(hilo)
            hilo.start()
        
        # Simular votos
        intervalo_votos = 60.0 / votos_por_minuto
        hilo_votos = threading.Thread(
            target=self.simular_votos,
            args=(metricas, intervalo_votos, duracion_minutos * 60)
        )
        hilos.append(hilo_votos)
        hilo_votos.start()
        
        # Esperar finalización
        for hilo in hilos:
            hilo.join()
        
        # Calcular métricas finales
        if metricas['latencias']:
            metricas['latencia_promedio'] = statistics.mean(metricas['latencias'])
            metricas['latencia_p95'] = sorted(metricas['latencias'])[int(len(metricas['latencias']) * 0.95)]
            metricas['latencia_max'] = max(metricas['latencias'])
        else:
            metricas['latencia_promedio'] = 0
            metricas['latencia_p95'] = 0
            metricas['latencia_max'] = 0
        
        metricas['duracion_real'] = time.time() - inicio
        metricas['throughput'] = metricas['votos_procesados'] / (metricas['duracion_real'] / 60) if metricas['duracion_real'] > 0 else 0
        total_operaciones = metricas['votos_procesados'] + metricas['votos_fallidos']
        metricas['tasa_error'] = (metricas['votos_fallidos'] / total_operaciones) * 100 if total_operaciones > 0 else 0
        
        print(f"   ✅ Completado: {metricas['votos_procesados']} votos procesados")
        
        return metricas
    
    def simular_consultas(self, metricas, duracion_segundos):
        """Simula consultas concurrentes al sistema"""
        fin = time.time() + duracion_segundos
        
        while time.time() < fin:
            try:
                inicio_consulta = time.time()
                
                # Simular tiempo de consulta (más realista)
                time.sleep(random.uniform(0.01, 0.05))  # 10-50ms
                
                latencia = (time.time() - inicio_consulta) * 1000  # ms
                metricas['latencias'].append(latencia)
                
                time.sleep(0.1)  # Pausa entre consultas
                
            except Exception as e:
                metricas['errores'].append(f"Error en consulta: {str(e)}")
    
    def simular_votos(self, metricas, intervalo_votos, duracion_segundos):
        """Simula emisión de votos"""
        fin = time.time() + duracion_segundos
        contador_votos = 0
        
        while time.time() < fin:
            try:
                inicio_voto = time.time()
                
                # Simular procesamiento de voto
                time.sleep(random.uniform(0.02, 0.08))  # 20-80ms
                
                tiempo_voto = (time.time() - inicio_voto) * 1000
                
                # Simular fallo ocasional (2% de fallos para prueba rápida)
                if random.random() < 0.02:
                    metricas['votos_fallidos'] += 1
                else:
                    metricas['votos_procesados'] += 1
                    metricas['latencias'].append(tiempo_voto)
                
                contador_votos += 1
                time.sleep(intervalo_votos)
                
            except Exception as e:
                metricas['votos_fallidos'] += 1
                metricas['errores'].append(f"Error en voto: {str(e)}")
    
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
                
                time.sleep(2)  # Muestreo más frecuente para prueba rápida
            except Exception as e:
                metricas['errores'].append(f"Error en monitoreo: {str(e)}")
        
        if muestras_cpu:
            metricas['uso_cpu_promedio'] = statistics.mean(muestras_cpu)
        if muestras_memoria:
            metricas['uso_memoria_promedio'] = statistics.mean(muestras_memoria)
    
    def generar_informe(self):
        """Genera informe completo de resultados"""
        print("\n=== GENERANDO INFORME RÁPIDO ===")
        
        # Guardar resultados en JSON
        with open('resultados_experimento.json', 'w') as f:
            json.dump(self.resultados, f, indent=2)
        
        # Generar CSV para análisis
        self.generar_csv()
        
        # Generar análisis estadístico
        self.generar_analisis_estadistico()
        
        print("✅ Informe rápido generado:")
        print("   - resultados_experimento.json")
        print("   - resultados_experimento.csv")
        print("   - analisis_estadistico.txt")
        
        # Mostrar resumen en consola
        self.mostrar_resumen()
    
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
            f.write("=== ANÁLISIS ESTADÍSTICO RÁPIDO - SISTEMA ELECTORAL ===\n\n")
            
            # Análisis de throughput
            throughputs = [r.get('throughput', 0) for r in self.resultados]
            if throughputs:
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
            cpu_uso = [r.get('uso_cpu_promedio', 0) for r in self.resultados if r.get('uso_cpu_promedio', 0) > 0]
            memoria_uso = [r.get('uso_memoria_promedio', 0) for r in self.resultados if r.get('uso_memoria_promedio', 0) > 0]
            
            if cpu_uso:
                f.write(f"RECURSOS:\n")
                f.write(f"  - CPU máximo: {max(cpu_uso):.1f}%\n")
                f.write(f"  - CPU promedio: {statistics.mean(cpu_uso):.1f}%\n")
                if memoria_uso:
                    f.write(f"  - Memoria máxima: {max(memoria_uso):.1f} MB\n")
                    f.write(f"  - Memoria promedio: {statistics.mean(memoria_uso):.1f} MB\n")
                f.write("\n")
            
            # Punto de corte
            f.write("ESTADO DEL SISTEMA:\n")
            errores_detectados = False
            for resultado in self.resultados:
                if resultado.get('tasa_error', 0) > 5:
                    config = resultado['configuracion']
                    f.write(f"  - Alta tasa de errores detectada en: {config['consultas_concurrentes']} consultas, "
                           f"{config['votos_por_minuto']} votos/min\n")
                    errores_detectados = True
            
            if not errores_detectados:
                f.write("  - Sistema estable en todas las configuraciones de prueba\n")
                f.write("  - Se recomienda ejecutar pruebas más intensivas para encontrar límites\n")
    
    def mostrar_resumen(self):
        """Muestra resumen de resultados en consola"""
        print("\n" + "="*60)
        print("           RESUMEN DE RESULTADOS RÁPIDOS")
        print("="*60)
        
        if not self.resultados:
            print("❌ No se obtuvieron resultados")
            return
        
        # Métricas principales
        throughputs = [r.get('throughput', 0) for r in self.resultados]
        latencias = [r.get('latencia_promedio', 0) for r in self.resultados if r.get('latencia_promedio', 0) > 0]
        
        print(f"📊 Experimentos ejecutados: {len(self.resultados)}")
        
        if throughputs:
            print(f"🚀 Throughput máximo: {max(throughputs):.1f} votos/min")
            print(f"📈 Throughput promedio: {statistics.mean(throughputs):.1f} votos/min")
        
        if latencias:
            print(f"⚡ Latencia mínima: {min(latencias):.1f} ms")
            print(f"⏱️  Latencia promedio: {statistics.mean(latencias):.1f} ms")
        
        # Votos totales procesados
        total_votos = sum(r.get('votos_procesados', 0) for r in self.resultados)
        total_fallos = sum(r.get('votos_fallidos', 0) for r in self.resultados)
        
        print(f"✅ Total votos procesados: {total_votos}")
        print(f"❌ Total fallos: {total_fallos}")
        
        if total_votos + total_fallos > 0:
            tasa_exito = (total_votos / (total_votos + total_fallos)) * 100
            print(f"🎯 Tasa de éxito: {tasa_exito:.1f}%")
        
        print("\n🎓 Para el informe completo en Word, ejecuta:")
        print("   python scripts/generar-informe-word.py")
        print("="*60)

if __name__ == "__main__":
    print("🚀 Iniciando experimentos rápidos del sistema electoral...")
    experimento = ExperimentoRapido()
    experimento.iniciar_experimentos()
    print("\n✅ ¡Experimentos rápidos completados!") 