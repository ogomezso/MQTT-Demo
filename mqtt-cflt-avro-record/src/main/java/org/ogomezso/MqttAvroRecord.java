package org.ogomezso;

import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;
import org.ogomezso.msg.AvroMessage;

public class MqttAvroRecord {

  public static void main(String[] args) throws IOException, InterruptedException {

    String topic = "cflt-record";
    String kafka_topic = "mqtt-avro";
    String mqttBroker = "20.238.230.187";

    Random rand = new Random();
    byte[] array = new byte[8];

    AvroMessage msg = AvroMessage.newBuilder()
        .setTemp(rand.nextInt(50))
        .setStation("station" + rand.nextInt(10))
        .setTime(rand.nextInt(100))
        .build();

    System.out.println(msg);

    HashMap<String, String> avroConfig = new HashMap<>();

    avroConfig.put(KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG,
        "https://psrc-4kk0p.westeurope.azure.confluent.cloud");
    avroConfig.put(KafkaAvroSerializerConfig.BASIC_AUTH_CREDENTIALS_SOURCE, "USER_INFO");
    avroConfig.put("basic.auth.user.info",
        "67HOWLYGDRHBQXP4:gJuk7OGlz3Kf+ugrjScjrB2VyYe7nVRVYlo/YPYfZuzfGw+wAR59PuVirndcGGL2");

    byte[] record = AvroMessageSerializer.serialize(avroConfig, kafka_topic, msg);

    System.out.println(Arrays.toString(record));

    EmqClient emqClient = new EmqClient(mqttBroker, topic);
    emqClient.publish(topic, record, false, kafka_topic);
    System.exit(0);

  }
}
