package org.ogomezso;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;

public class EmqClient {

  MqttClient mqttClient;

  public EmqClient(String broker, String clientId) throws MqttException {
    this.mqttClient = new MqttClient(broker, clientId);
  }

  public void publish(String topic, int qos, byte[] message, boolean retain) throws MqttException {
    mqttClient.connect();
    mqttClient.publish(topic, message, qos, retain);
  }
}
