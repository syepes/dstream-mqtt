/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.streaming.mqtt

import javax.net.ssl.SSLContext

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.dstream._
import org.apache.spark.streaming.receiver.Receiver

/**
 * Input stream that subscribe messages from a Mqtt Broker.
 * Uses eclipse paho as MqttClient http://www.eclipse.org/paho/
 * @param brokerUrl Url of remote mqtt publisher
 * @param topic topic name to subscribe to
 * @param storageLevel RDD storage level.
 */

private[streaming]
class MQTTInputDStream(
    _ssc: StreamingContext,
    brokerUrl: String,
    topic: String,
    clientID: String,
    cleanSession: Boolean,
    userName: String,
    password: String,
    storageLevel: StorageLevel
  ) extends ReceiverInputDStream[String](_ssc) {

  private[streaming] override def name: String = s"MQTT stream [$id]"

  def getReceiver(): Receiver[String] = {
    new MQTTReceiver(brokerUrl, topic, clientID, cleanSession, userName, password, storageLevel)
  }
}

private[streaming]
class MQTTReceiver(
    brokerUrl: String,
    topic: String,
    clientID: String,
    cleanSession: Boolean,
    userName: String,
    password: String,
    storageLevel: StorageLevel
  ) extends Receiver[String](storageLevel) {

  // Connection to MqttBroker
  var client: MqttClient = null

  def onStop() {
    if (client != null) {
      client.disconnect()
      client = null
    }
  }

  def onStart() {

    // Set up persistence for messages
    val persistence = new MemoryPersistence()

    // AutoGenerate ClientID if not specified
    var clientId = clientID
    if(clientId == null) {
      clientId = MqttClient.generateClientId()
    }

    // Initializing Mqtt Client specifying brokerUrl, clientID and MqttClientPersistance
    client = new MqttClient(brokerUrl, clientId, persistence)

    val connectOptions = new MqttConnectOptions()

    // Should Client/Broker remember state across restarts and reconnects
    if (cleanSession) {
      connectOptions.setCleanSession(true)
    } else {
      connectOptions.setCleanSession(false)
    }

    // Create a secure connection
    if (brokerUrl.startsWith("ssl://")) {
      val sslContext = SSLContext.getInstance("TLSv1.2")
      sslContext.init(null, null, null)
      connectOptions.setSocketFactory(sslContext.getSocketFactory())
    }

    if(userName != null && password != null) {
      connectOptions.setUserName(userName)
      connectOptions.setPassword(password.toCharArray())
    }

    // Callback automatically triggers as and when new message arrives on specified topic
    val callback = new MqttCallback() {

      // Handles Mqtt message
      override def messageArrived(topic: String, message: MqttMessage) {
        store(new String(message.getPayload(), "utf-8"))
      }

      override def deliveryComplete(token: IMqttDeliveryToken) {
      }

      override def connectionLost(cause: Throwable) {
        restart("Connection lost ", cause)
      }
    }

    // Set up callback for MqttClient. This needs to happen before
    // connecting or subscribing, otherwise messages may be lost
    client.setCallback(callback)

    // Connect to MqttBroker
    client.connect(connectOptions)

    // Subscribe to Mqtt topic
    client.subscribe(topic)

  }
}
