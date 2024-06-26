# CFK

Para este playground se ha instalado Kafka Connect en AKS

- `Confluent Operator`en versión: 0.921.20

```bash
helm repo update
helm upgrade --install confluent-operator confluentinc/confluent-for-kubernetes --namespace confluent
```

- `Kafka Connect` teniendo como backend un Confluent Cloud desplegado en la misma VNET linkada con la red Confluent.

## Kafka Connect

Para Kafka Connect usaremos la versión `7.6.1` con init-container `2.8.2`

### Secretos para CCLOUD

Para autenticar con CCLOUD necesitaremos tener las API-KEYS adecuadas.

Para ello necesitamos crear los secretos adecuados:

**Credenciales para Cluster Kafka**

```bash
kubectl create secret generic ccloud-credentials --from-file=plain.txt=ccloud-credentials.txt -n confluent --dry-run=client -o yaml > ccloud-credentials.yaml
```

donde `ccloud-credentials.txt` contiene:

```txt
username=<CCLOUD-API-KEY>
password=<CCLOUD-API-SECRET>
```

En caso de necesitar crear Topics desde el cluster AKS, necesitaremos una rest credential

```bash
kubectl create secret generic ccloud-rest-credentials --from-file=basic.txt=ccloud-credentials.txt --namespace confluent --dry-run=client -o yaml > ccloud-rest-credentials.yaml
```

**Credenciales Schema Registry**

```bash
kubectl create secret generic ccloud-sr-credentials --from-file=basic.txt=ccloud-sr-credentials.txt -n confluent  --dry-run=client -o yaml > ccloud-sr-credentials.yaml
```

donde `ccloud-credentials.txt` contiene:

```txt
username=<CCLOUD-API-KEY>
password=<CCLOUD-API-SECRET>
```

Esto generá los recursos yaml para crear los secretos en el namespace `confluent`, por tanto solo nos queda aplicarlos:

```bash
kubectl apply -f ccloud-credentials.yaml
kubectl apply -f ccloud-sr-credentials.yaml
```

### Connect Deployment

Usaremos el CRD de `Connect` para deplegar nuestro cluster de connect, con varias cosas a tener en cuenta:

1. Clausula `build` que instala el plugin para MQTT Source Connector en la propia instalación:

```yaml
  build:
    type: onDemand
    onDemand:
      plugins:
        locationType: confluentHub
        confluentHub:
          - name: kafka-connect-mqtt
            owner: confluentinc
            version: 1.7.0
          - name: kafka-connect-datagen
            owner: confluentinc
            version: 0.4.0
```

2. Argumento extra de JVM para dar permisos al usuario de los conectores a escribir (necesario para el MQTT Source Connector):

```yaml
  configOverrides:
    jvm:
      - "-Duser.dir=/tmp/"
```
Con esto tendremos nuestro Worker de Connect preparado para crear replicar datos a un topic.

### Connector

Utilizaremos el CRD `Connector` para crear el connector MQTT con las siguientes configuraciones:

```yaml
    key.converter: "org.apache.kafka.connect.storage.StringConverter"
    value.converter: "org.apache.kafka.connect.converters.ByteArrayConverter"
    mqtt.topics: "hello,bye"
    kafka.topic: hello
    mqtt.server.uri: "tcp://20.238.230.187:1883"
    mqtt.qos: "0"
    mqtt.clean.session.enabled: "false"
```

a destacar:

- Usamos String para deserializar la Key de los mensaje a producir
- ByteArray de modo que no convertimos el mensaje tal cual llega de MQTT
- `mqtt.topics`: lista de topics a leer del servidor MQTT
- `kafka.topic`: Tópic Confluent donde volcaremos los datos de los topic MQTT
- `mqtt.clean.session.enabled`: Deshabilitamos el cleanup de la session en MQTT para asegurar la semántica at-least-once

### Kafka Topic

Usamos el CRD Topic para crear el Kafka Topic usado por el connector.

### EMQX

Instalamos el core de EMQX haciendo uso de su operador:

```bash
helm repo add emqx https://repos.emqx.io/charts
helm repo update
helm upgrade --install emqx-operator emqx/emqx-operator
--namespace emqx-operator-system
--create-namespace
```

Y un broker EMQ -> `emqx-broker,yaml`

### Test

#### String MSG

Aplicamos el recurso `hello-mqtt-connector.yaml`

Usaremos el cliente `mqttx` para producir mensajes dos topics mqtt `hello` y `bye`: 

```bash
mqttx pub -t 'hello' -h 20.238.230.187 -p 1883 -m'{"greeting": "Hello World"}'
mqttx pub -t 'bye' -h 20.238.230.187 -p 1883 -m '{"greeting": "Bye bye World"}'
```

Usaremos el cliente confluent desplegado con el recurso `confluent-cli.yaml` para ver los mensajes producidos al topic `hello`:

```bash
confluent login --save
confluent kafka topic consume hello --cluster lkc-m5ogd2 --api-key CIJWRHA2XP2VRTI4 --api-secret yGo0hM1pmpm84AMfmvvuHWvIAAeCT3JN11bx6G0deBUC6j1WrGb1+6PRCmOAfPV8 -b
````

```bash
Starting Kafka Consumer. Use Ctrl-C to exit.
{"greeting": "Bye bye World"}
% Headers: [mqtt.message.id="0" mqtt.qos="0" mqtt.retained="false" mqtt.duplicate="false"]
{"greeting": "Bye bye World"}
% Headers: [mqtt.message.id="0" mqtt.qos="0" mqtt.retained="false" mqtt.duplicate="false"]
{"greeting": "Bye bye World"}
% Headers: [mqtt.message.id="0" mqtt.qos="0" mqtt.retained="false" mqtt.duplicate="false"]
{"greeting": "Hello World"}
% Headers: [mqtt.message.id="0" mqtt.qos="0" mqtt.retained="false" mqtt.duplicate="false"]
{"greeting": "Hello World"}
% Headers: [mqtt.message.id="0" mqtt.qos="0" mqtt.retained="false" mqtt.duplicate="false"]
{"greeting": "Hello World"}
% Headers: [mqtt.message.id="0" mqtt.qos="0" mqtt.retained="false" mqtt.duplicate="false"]
{"greeting": "Bye bye World"}
% Headers: [mqtt.message.id="0" mqtt.qos="0" mqtt.retained="false" mqtt.duplicate="false"]
{"greeting": "Hello World"}
% Headers: [mqtt.message.id="0" mqtt.qos="0" mqtt.retained="false" mqtt.duplicate="false"]
```

### AVRO MSG

La carpeta `mqtt-cflt-avro-record` contiene un proyecto Java en el que hacemos uso del `KafkaAvroSerializer` configurado con los datos de acceso del cluster de Schema Registry en Confluent Cloud para serializar los mensajes que enviamos a `EMQX` siendo compatibles con la serialización confluent y por tanto usables desde el conector MQTT.

Como primer paso generamos una clase AVRO Message usando el `Avro Maven Plugin` esta clase se genera en tiempo de construcción del proyecto maven.

El código de serialización se encuentra en la clase `AvroMessageSerializer`.

La clase `MqttAvroRecord` genera un mensaje aleatorio usando el POJO Avro generado con el plugin y lo publica en un topic MQTT

Lo siguiente es configurar nuestro conector:

```yaml
apiVersion: platform.confluent.io/v1beta1
kind: Connector
metadata:
  name: mqtt-avro
  namespace: confluent
spec:
  class: "io.confluent.connect.mqtt.MqttSourceConnector"
  taskMax: 1
  connectClusterRef:
    name: connect
  configs:
    key.converter: "org.apache.kafka.connect.storage.StringConverter"
    value.converter: "org.apache.kafka.connect.converters.ByteArrayConverter"
    mqtt.topics: "cflt-record"
    kafka.topic: "mqtt-avro"
    mqtt.server.uri: "tcp://20.238.230.187:1883"
    mqtt.qos: "0"
    mqtt.clean.session.enabled: "false"
```

Es importante destacar que al escribir el mensaje ya serializado y en bytearray directamente en el topic mqtt y no existir soporte de esquema en el, el conector no hará ninguna conversion (de ahi el uso del ByteArrayConverter).

Podemos usar el `confluent cli` para consumir los mensajes del topic destino ya en avro:

```bash
confluent-cli-fc9f87dc6-zncfc:/$ confluent kafka topic consume mqtt-avro --cluster lkc-m5ogd2 --api-key CIJWRHA2XP2VRTI4 --api-secret yGo0hM1pmpm84AMfmvvuHWvIAAeCT3JN11bx6G0deBUC6j1WrGb1+6PRCmOAfPV8 --value-format avro --schema-registry-endpoint https://psrc-4kk0p.westeurope.azure.confluent.cloud --schema-registry-api-key 67HOWLYGDRHBQXP4 --schema-registry-api-secret gJuk7OGlz3Kf+ugrjScjrB2VyYe7nVRVYlo/YPYfZuzfGw+wAR59PuVirndcGGL2 -b
```

```text
confluent-cli-fc9f87dc6-zncfc:/$ confluent login --save
Logged in as "ogomezsoriano+svcs-cemea@confluent.io" for organization "c9bc4776-9ab9-473b-8b16-c76108251133" ("Confluent PS Service CEMEA").
confluent-cli-fc9f87dc6-zncfc:/$ confluent kafka topic consume mqtt-avro --cluster lkc-m5ogd2 --api-key CIJWRHA2XP2VRTI4 --api-secret yGo0hM1pmpm84AMfmvvuHWvIAAeCT3JN11bx6G0deBUC6j1WrGb1+6PRCmOAfPV8 --value-format avro --schema-registry-endpoint https://psrc-4kk0p.westeurope.azure.confluent.cloud --schema-registry-api-key 67HOWLYGDRHBQXP4 --schema-registry-api-secret gJuk7OGlz3Kf+ugrjScjrB2VyYe7nVRVYlo/YPYfZuzfGw+wAR59PuVirndcGGL2 -b
Starting Kafka Consumer. Use Ctrl-C to exit.
{"station":"station","time":1718889958926,"temp":1}
% Headers: [mqtt.message.id="0" mqtt.qos="0" mqtt.retained="true" mqtt.duplicate="false"]
{"station":"station","time":1718897073658,"temp":1}
% Headers: [mqtt.message.id="0" mqtt.qos="0" mqtt.retained="false" mqtt.duplicate="false"]
{"temp":7,"station":"station9","time":920}
% Headers: [mqtt.message.id="0" mqtt.qos="0" mqtt.retained="false" mqtt.duplicate="false"]
```

### Enrutado de topics en base a cabeceras

El objetivo de la funcionalidad es que el conector MQTT sea capaz de producir cada mensaje MQTT en el topic Kafka adecuado usando para ello el contenido de la cabecera `topicName` que contiene el nombre del topic kafka destino.

Para ello usaremos la SMT `io.confluent.connect.transforms.ExtractTopic$Header`.

Por tanto necesitaremos instalar el plugin `connect-transforms`, añadiendolo a la cláusula build del CR de connect:

```yaml
  build:
    type: onDemand
    onDemand:
      plugins:
        locationType: confluentHub
        confluentHub:
          - name: kafka-connect-mqtt
            owner: confluentinc
            version: 1.7.0
          - name: kafka-connect-datagen
            owner: confluentinc
            version: 0.4.0
          - name: connect-transforms
            owner: confluentinc
            version: latest
```

A partir de aquí podemos usarlo facilmente en la configuración de nuestro conector:

```yaml
---
apiVersion: platform.confluent.io/v1beta1
kind: Connector
metadata:
  name: mqtt-avro
  namespace: confluent
spec:
  class: "io.confluent.connect.mqtt.MqttSourceConnector"
  taskMax: 1
  connectClusterRef:
    name: connect
  configs:
    key.converter: "org.apache.kafka.connect.storage.StringConverter"
    value.converter: "org.apache.kafka.connect.converters.ByteArrayConverter"
    mqtt.topics: "cflt-record"
    kafka.topic: "best-topic-ever"
    mqtt.server.uri: "tcp://20.238.230.187:1883"
    mqtt.qos: "0"
    mqtt.clean.session.enabled: "false"
    transforms: "TopicName"
    transforms.TopicName.type: "io.confluent.connect.transforms.ExtractTopic$Header"
    transforms.TopicName.field: "topicName"
```

