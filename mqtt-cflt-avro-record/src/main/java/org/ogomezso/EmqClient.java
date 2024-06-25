package org.ogomezso;


import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;

public class EmqClient {

  Mqtt5BlockingClient client;

  public EmqClient(String broker, String clientId) {
    this.client = Mqtt5Client.builder()
        .identifier(clientId)
        .serverHost(broker)
        .buildBlocking();

  }

  public void publish(String topic, byte[] message, boolean retain, String kafkaTopic) {
    client.connect();
    client.publishWith()
        .topic(topic)
        .payload(message)
        .qos(MqttQos.AT_LEAST_ONCE)
        .userProperties().add("topicName", kafkaTopic).applyUserProperties()
        .send();
  }
}
