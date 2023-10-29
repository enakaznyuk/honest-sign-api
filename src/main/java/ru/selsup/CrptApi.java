package ru.selsup;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class CrptApi {

    public static void main(String[] args) {

        CrptClientThread thread = new CrptClientThread("Milk thread");
        System.out.println("start\n");
        thread.start();
    }

    public static class CrptJsonParser {

        static ObjectMapper objectMapper = new ObjectMapper();

        public static String parseObjectToJson(ProductInformation doc) throws JsonProcessingException {

            String json = objectMapper.writeValueAsString(doc);
            System.out.println(json);

            return json;
        }

        public static String getTokenFromJson(String str, String key) {

            JSONObject jsonObject = new JSONObject(str);
            return (String) jsonObject.get(key);
        }
    }

    public static class Connection {

        public String getAuthorizationsJson() throws IOException, InterruptedException {

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .uri(URI.create("https://markirovka.demo.crpt.tech/api/v3/auth/cert/key"))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("Status code: " + response.statusCode());
            System.out.println("\n Body: " + response.body());
            return response.body();
        }

        public String getToken(String json) throws IOException, InterruptedException {

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .uri(URI.create("https://markirovka.demo.crpt.tech/api/v3/auth/cert/"))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("Status code: " + response.statusCode());
            System.out.println("\n Body: " + response.body());
            return CrptJsonParser.getTokenFromJson(response.body(), "token");
        }

        public void sendDocuments(String json, String token) throws IOException, InterruptedException {

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .header("Content-Type", "application/json")
                    .header("Authorization:", "Bearer " + token)
                    .uri(URI.create("https://markirovka.demo.crpt.tech/api/v3/lk/documents/create"))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("Status code: " + response.statusCode());
            System.out.println("\n Body: " + response.body());
        }
    }

    public static class ConnectionPool {

        private int POOL_SIZE = 3;
        private int TIME_IN_SECONDS = 1;
        private final BlockingQueue<Connection> freeConnections;
        private final BlockingQueue<Connection> engageConnections;

        private volatile static ConnectionPool instance;

        private ConnectionPool() {
            freeConnections = new ArrayBlockingQueue<>(POOL_SIZE, true);
            engageConnections = new ArrayBlockingQueue<>(POOL_SIZE, true);
        }

        public static ConnectionPool getInstance(int POOL_SIZE, int TIME_UNIT) throws InterruptedException {
            if (instance == null) {
                synchronized (ConnectionPool.class) {
                    if (instance == null) {
                        instance = new ConnectionPool();
                        instance.POOL_SIZE = POOL_SIZE;
                        instance.TIME_IN_SECONDS = TIME_UNIT;
                        instance.setFreeConnections();
                    }
                }
            }
            return instance;
        }

        public static ConnectionPool getInstance() throws InterruptedException {
            if (instance == null) {
                synchronized (ConnectionPool.class) {
                    if (instance == null) {
                        instance = new ConnectionPool();
                        instance.setFreeConnections();
                    }
                }
            }
            return instance;
        }

        public synchronized Connection getConnection() throws InterruptedException {
            Connection connection;
            connection = freeConnections.take();
            engageConnections.put(connection);
            return connection;
        }

        private void setFreeConnections() throws InterruptedException {
            for (int i = 0; i < POOL_SIZE; i++) {
                freeConnections.put(new Connection());
            }
            clearThreadQueues();
        }

        private void clearThreadQueues() {
            Thread myThread = new Thread(() -> {
                while (true) {
                    try {
                        engageConnections.take();
                        TimeUnit.SECONDS.sleep(TIME_IN_SECONDS / POOL_SIZE);
                        freeConnections.put(new Connection());
                    } catch (InterruptedException | IllegalStateException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
            myThread.setDaemon(true);
            myThread.start();
        }
    }

    public static class CrptClientThread extends Thread {

        CrptClientThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            try {

                Scanner scanner = new Scanner(System.in);
                System.out.println("Input size of queue:");
                int size = scanner.nextInt();
                System.out.println("Input time of queue:");
                int time = scanner.nextInt();
                ConnectionPool connectionPool = ConnectionPool.getInstance(size, time);
                Connection conn = connectionPool.getConnection();

                ProductInformation milk = new ProductInformation();
                milk.setProductDocument(Base64.getEncoder().encodeToString("information".getBytes()));
                milk.setProductGroup("milk");
                String request = conn.getAuthorizationsJson();
                String signature = CrptJsonParser.getTokenFromJson(request, "data");
                milk.setSignature(Base64.getEncoder().encodeToString(signature.getBytes()));
                milk.setType("LP_INTRODUCE_GOODS");
                milk.setDocumentFormat(ProductInformation.DocumentFormat.MANUAL);


                String token = conn.getToken(request);
                conn.sendDocuments(CrptJsonParser.parseObjectToJson(milk), token);
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @JsonPropertyOrder({"documentFormat", "productDocument", "productGroup", "signature", "type"})
    public static class ProductInformation {

        public enum DocumentFormat {
            MANUAL
        }

        DocumentFormat documentFormat;
        String productDocument;
        String signature;
        String productGroup;
        String type;

        @JsonGetter("product_document")
        public String getProductDocument() {
            return productDocument;
        }

        public void setProductDocument(String productDocument) {
            this.productDocument = productDocument;
        }

        @JsonGetter("signature")
        public String getSignature() {
            return signature;
        }

        public void setSignature(String signature) {
            this.signature = signature;
        }

        @JsonGetter("product_group")
        public String getProductGroup() {
            return productGroup;
        }

        public void setProductGroup(String productGroup) {
            this.productGroup = productGroup;
        }

        @JsonGetter("type")
        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        @JsonGetter("document_format")
        public DocumentFormat getDocumentFormat() {
            return documentFormat;
        }

        public void setDocumentFormat(DocumentFormat documentFormat) {
            this.documentFormat = documentFormat;
        }

        @Override
        public String toString() {
            return "ProductInformation{" +
                    "documentFormat=" + documentFormat +
                    ", productDocument='" + productDocument + '\'' +
                    ", signature='" + signature + '\'' +
                    ", productGroup='" + productGroup + '\'' +
                    ", type='" + type + '\'' +
                    '}';
        }
    }
}


