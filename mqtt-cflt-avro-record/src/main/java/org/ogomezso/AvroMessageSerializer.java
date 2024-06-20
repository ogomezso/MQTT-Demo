package org.ogomezso;


import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.util.HashMap;
import org.ogomezso.msg.AvroMessage;

public class AvroMessageSerializer {

  public static byte[] serialize(HashMap<String, String> avroConfig, String topic, AvroMessage avroMessage) {
    final KafkaAvroSerializer serializer = new KafkaAvroSerializer();
    serializer.configure(avroConfig, false);
    return serializer.serialize(topic, avroMessage);
  }
}
