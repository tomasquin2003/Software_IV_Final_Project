# 📊 Experimentos de Rendimiento - Sistema Electoral Distribuido

## 🎯 Objetivo

Evaluar el rendimiento, escalabilidad y confiabilidad del sistema electoral bajo diferentes condiciones de carga, analizando métricas clave como throughput, latencia, uso de recursos y tolerancia a fallos.

## 🏗️ Diseño Experimental

### Variables Independientes

| Variable | Valores | Descripción |
|----------|---------|-------------|
| **Consultas Concurrentes** | 10, 50, 100, 200, 500 | Número de consultas simultáneas |
| **Votos por Minuto** | 100, 500, 1000, 2000, 5000 | Tasa de generación de votos |
| **Duración** | 5, 10, 15, 30 minutos | Tiempo de ejecución |

### Variables Dependientes

| Métrica | Unidad | Descripción |
|---------|--------|-------------|
| **Throughput** | votos/min | Capacidad de procesamiento |
| **Latencia Promedio** | ms | Tiempo de respuesta medio |
| **Latencia P95** | ms | Percentil 95 de latencia |
| **Tasa de Error** | % | Porcentaje de fallos |
| **Uso CPU** | % | Consumo de procesador |
| **Uso Memoria** | MB | Consumo de RAM |

## 🚀 Ejecución Rápida

### Opción 1: Automática (Recomendada)
```bash
cd sistema-votacion
scripts/ejecutar-experimentos.bat
```

### Opción 2: Manual

1. **Instalar dependencias:**
```bash
pip install -r requirements.txt
```

2. **Ejecutar experimentos:**
```bash
python scripts/experimento-rendimiento.py
```

3. **Generar informe:**
```bash
python scripts/generar-informe-word.py
```

## 📋 Configuraciones Experimentales

### Configuración Ligera (Prueba Rápida)
```python
configuraciones = [
    (10, 100, 2),   # 10 consultas, 100 votos/min, 2 min
    (50, 500, 3),   # 50 consultas, 500 votos/min, 3 min
    (100, 1000, 5)  # 100 consultas, 1000 votos/min, 5 min
]
```

### Configuración Estándar (Completa)
```python
configuraciones = [
    (10, 100, 5),     # Carga baja
    (50, 500, 5),     # Carga media-baja
    (100, 1000, 10),  # Carga media
    (200, 2000, 15),  # Carga alta
    (500, 5000, 30)   # Carga extrema
]
```

### Configuración Intensiva (Estrés)
```python
configuraciones = [
    (100, 1000, 15),
    (200, 2000, 20),
    (300, 3000, 25),
    (500, 5000, 30),
    (1000, 10000, 60)  # Prueba de límites
]
```

## 📊 Resultados Esperados

### Archivos Generados

1. **`resultados_experimento.json`** - Datos detallados en formato JSON
2. **`resultados_experimento.csv`** - Datos tabulados para análisis
3. **`analisis_estadistico.txt`** - Resumen estadístico
4. **`Informe_Experimentos_SistemaElectoral_YYYYMMDD_HHMM.docx`** - Informe completo en Word

### Estructura del Informe Word

1. **Portada**
2. **Resumen Ejecutivo**
3. **Introducción**
4. **Metodología Experimental**
5. **Resultados Experimentales**
6. **Análisis Estadístico**
7. **Análisis Gráfico**
8. **Conclusiones**
9. **Recomendaciones**
10. **Anexos Técnicos**

## 📈 Métricas de Interés

### Puntos de Saturación
- **CPU > 85%**: Sistema bajo estrés
- **Latencia > 1000ms**: Degradación del servicio
- **Tasa Error > 5%**: Pérdida de confiabilidad

### Umbrales de Rendimiento
- **Throughput objetivo**: > 2000 votos/min
- **Latencia objetivo**: < 200ms (P95)
- **Disponibilidad objetivo**: > 99.9%

## 🔧 Personalización

### Modificar Configuraciones

Edita `scripts/experimento-rendimiento.py`:

```python
class ExperimentoSistemaElectoral:
    def __init__(self):
        self.configuraciones = [
            # (consultas, votos_min, duracion_min)
            (TUS_VALORES_AQUI)
        ]
```

### Agregar Métricas Personalizadas

```python
def nueva_metrica(self, metricas):
    # Tu lógica de medición aquí
    return valor_metrica
```

## 🎯 Análisis de Resultados

### Interpretación de Gráficos

1. **Throughput vs Consultas**
   - Pendiente positiva = Buena escalabilidad
   - Plateau = Punto de saturación alcanzado

2. **Latencia vs Carga**
   - Crecimiento lineal = Normal
   - Crecimiento exponencial = Saturación

3. **Recursos vs Tiempo**
   - Estable = Sistema balanceado
   - Creciente = Posible memory leak

### Identificación de Cuellos de Botella

- **CPU alto + Latencia alta** = Procesamiento limitado
- **Memoria alta + Errores** = Problemas de memoria
- **Tasa error alta** = Fallos de comunicación

## 🚨 Troubleshooting

### Error: Dependencias no instaladas
```bash
pip install --upgrade pip
pip install -r requirements.txt
```

### Error: Puerto en uso
```bash
netstat -ano | findstr :1000
taskkill /PID [PID_NUMERO] /F
```

### Error: Permisos insuficientes
```bash
# Ejecutar como administrador
# O cambiar directorio de salida
```

### Error: Memoria insuficiente
- Reducir duración de experimentos
- Usar configuración ligera
- Cerrar aplicaciones innecesarias

## 📝 Plantilla de Análisis

### Para el Informe Académico

```markdown
## Resultados Obtenidos

### Throughput Máximo
- Valor: X votos/minuto
- Configuración: Y consultas, Z votos/min

### Latencia Mínima  
- Valor: X ms
- Condiciones: Carga baja/media/alta

### Punto de Saturación
- Detectado en: X consultas concurrentes
- Síntomas: Latencia > Yms, Errores > Z%

### Conclusiones
1. El sistema soporta hasta X votos/minuto
2. La latencia se mantiene bajo control hasta Y consultas
3. El VotosBroker demostró tolerancia a fallos efectiva
```

## 🎓 Para Entrega Académica

### Elementos Requeridos
- ✅ Diseño experimental documentado
- ✅ Variables independientes y dependientes
- ✅ Metodología de medición
- ✅ Resultados tabulados
- ✅ Análisis estadístico
- ✅ Gráficos de rendimiento
- ✅ Identificación del punto de corte
- ✅ Conclusiones y recomendaciones

### Formato de Entrega
1. **Informe Word** (principal)
2. **Datos CSV** (para verificación)
3. **Código fuente** (reproducibilidad)
4. **Capturas de pantalla** (evidencia)

## 📞 Soporte

Si encuentras problemas:
1. Revisa los logs en la consola
2. Verifica que todos los componentes estén ejecutándose
3. Confirma que los puertos estén libres
4. Asegúrate de tener Python 3.7+ instalado 