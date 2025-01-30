package com.fitmymacros;

import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.HttpStatusCode;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitmymacros.model.ChatCompletionResponse;
import com.fitmymacros.model.ChatCompletionResponseChoice;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

public class OpenAILambda implements RequestHandler<Map<String, Object>, Object> {

    private static final String OPENAI_API_KEY_NAME = "OpenAI-API_Key_Encrypted";
    private static final String OPENAI_MODEL_NAME = "OpenAI-Model-CalorieAdapter";
    private static final String OPENAI_MODEL_TEMPERATURE = "OpenAI-Model-Temperature-CalorieAdapter";
    private static final String OPENAI_MAX_TOKENS = "OpenAI-Max-Tokens";
    private static final String RESULT_TABLE_NAME = "FitMyMacros_OpenAI_Results";
    private static final String URL = "https://api.openai.com/v1/chat/completions";
    private static final List<String> fruitUnits = Arrays.asList(
        "Apple", "Banana", "Orange", "Peach", "Kiwi", "Pear", "Cherry", "Plum", "Apricot", "Papaya", "Avocado",
        "Grapefruit", "Lemon", "Lime", "Tangerine", "Cantaloupe", "Honeydew melon", "Nectarine", "Persimmon",
        "Dragon fruit", "Jackfruit", "Star fruit", "Ackee", "Plantain", "Coconut", "Mangosteen", "Feijoa",
        "Kumquat", "Pummelo", "Satsuma", "Ugli fruit");

    private final SsmClient ssmClient;
    private final DynamoDbClient dynamoDbClient;
    private final String OPENAI_AI_KEY;
    private final String OPENAI_MODEL;
    private final Double MODEL_TEMPERATURE;
    private final Integer MODEL_MAX_TOKENS;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public OpenAILambda() {
        this.ssmClient = SsmClient.builder().region(Region.EU_WEST_3).build();
        this.dynamoDbClient = DynamoDbClient.builder().region(Region.EU_WEST_3).build();
        this.OPENAI_AI_KEY = getOpenAIKeyFromParameterStore();
        this.OPENAI_MODEL = getOpenAIModelFromParameterStore();
        this.MODEL_TEMPERATURE = getTemperatureFromParameterStore();
        this.MODEL_MAX_TOKENS = getMaxTokensFromParameterStore();
        this.objectMapper = new ObjectMapper();
        this.webClient = WebClient.create();
    }

    @Override
    public Object handleRequest(Map<String, Object> input, Context context) {
        try {
            Map<String, String> queryParams = extractQueryString(input);
            if (queryParams == null) {
                return buildErrorResponse("Invalid query parameters");
            }

            String opId = queryParams.get("opId");
            String prompt = generatePrompt(queryParams);
            Map<String, Object> requestBody = buildRequestBody(prompt);

            ChatCompletionResponse completionResponse = callOpenAI(requestBody);
            if (completionResponse != null) {
                ChatCompletionResponseChoice choice = completionResponse.getChoices().get(0);
                putItemInDynamoDB(opId, choice.getMessage().getContent());
                return buildSuccessResponse(choice.getMessage().getContent());
            } else {
                return buildErrorResponse("Failed to retrieve completion response");
            }
        } catch (Exception e) {
            return buildErrorResponse(e.getMessage());
        }
    }

    private Map<String, String> extractQueryString(Map<String, Object> input) {
        Map<String, Object> queryStringMap = (Map<String, Object>) input.get("queryStringParameters");
        if (queryStringMap == null || !queryStringMap.containsKey("querystring")) {
            System.out.println("No query string parameters found.");
            return null;
        }
        return parseQueryString((String) queryStringMap.get("querystring"));
    }

    private Map<String, String> parseQueryString(String queryString) {
        Map<String, String> queryMap = new HashMap<>();
        if (queryString.startsWith("{") && queryString.endsWith("}")) {
            queryString = queryString.substring(1, queryString.length() - 1);
        }
        String[] pairs = queryString.split(", ");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=");
            queryMap.put(keyValue[0], (keyValue.length > 1) ? keyValue[1] : "true");
        }
        return queryMap;
    }

    private String getOpenAIKeyFromParameterStore() {
        return getParameterValue(OPENAI_API_KEY_NAME);
    }

    private String getOpenAIModelFromParameterStore() {
        return getParameterValue(OPENAI_MODEL_NAME);
    }

    private Double getTemperatureFromParameterStore() {
        return Double.valueOf(getParameterValue(OPENAI_MODEL_TEMPERATURE));
    }

    private Integer getMaxTokensFromParameterStore() {
        return Integer.valueOf(getParameterValue(OPENAI_MAX_TOKENS));
    }

    private String getParameterValue(String parameterName) {
        try {
            GetParameterRequest parameterRequest = GetParameterRequest.builder()
                .name(parameterName)
                .withDecryption(true)
                .build();
            GetParameterResponse parameterResponse = ssmClient.getParameter(parameterRequest);
            return parameterResponse.parameter().value();
        } catch (SsmException e) {
            System.out.println("SSM Error: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private String generatePrompt(Map<String, String> input) {
        String recipeName = input.get("recipeName");
        String userId = input.get("userId");
        String measureUnit = input.get("measureUnit");
        int calories = Integer.parseInt(input.get("calories"));
        int protein = Integer.parseInt(input.get("protein"));
        int carbs = Integer.parseInt(input.get("carbs"));
        int fat = Integer.parseInt(input.get("fat"));
        String precision = input.get("precision");
        boolean glutenFree = Boolean.parseBoolean(input.get("glutenFree"));
        boolean vegan = Boolean.parseBoolean(input.get("vegan"));
        boolean vegetarian = Boolean.parseBoolean(input.get("vegetarian"));
        String cookingTime = input.get("cookingTime");
        boolean anyIngredientsMode = Boolean.parseBoolean(input.get("anyIngredientsMode"));

        QueryResponse queryResponse = getUserData(userId);
        Map<String, AttributeValue> userData = queryResponse.items().get(0);
        return createPrompt(anyIngredientsMode, recipeName, precision, measureUnit, calories, protein, carbs, fat,
                glutenFree, vegan, vegetarian, cookingTime, userData);
    }

    private QueryResponse getUserData(String userId) {
        try {
            Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
            expressionAttributeValues.put(":uid", AttributeValue.builder().s(userId).build());
            String keyConditionExpression = "userId = :uid";

            QueryRequest queryRequest = QueryRequest.builder()
                .tableName("FitMyMacros")
                .keyConditionExpression(keyConditionExpression)
                .expressionAttributeValues(expressionAttributeValues)
                .build();

            return dynamoDbClient.query(queryRequest);
        } catch (DynamoDbException e) {
            System.out.println("User data exception: " + e.getMessage());
            throw new RuntimeException("Error retrieving data from DynamoDB: " + e.getMessage());
        }
    }

    private String createPrompt(boolean anyIngredientsMode, String recipeName, String precision, String measureUnit,
                                int calories, int protein, int carbs, int fat, boolean glutenFree,
                                boolean vegan, boolean vegetarian, String cookingTime, Map<String, AttributeValue> userData) {
                                
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append(String.format("Adapt this recipe: \"%s\" to have %s %d %s of protein, %d %s of carbs and %d %s of fat",
            recipeName, precision, protein, measureUnit, carbs, measureUnit, fat, measureUnit));

        if (!anyIngredientsMode) {
            appendAvailableIngredients(promptBuilder, userData);
        }

        appendAllergens(promptBuilder, userData);
        appendDietaryPreferences(promptBuilder, userData, vegan, vegetarian);
        appendDietType(promptBuilder, userData);
        appendAvailableEquipment(promptBuilder, userData);

        return promptBuilder.toString();
    }

    private void appendAvailableIngredients(StringBuilder promptBuilder, Map<String, AttributeValue> userData) {
        promptBuilder.append(". Use only available ingredients: ");
        Map<String, AttributeValue> foodMap = userData.get("food").m();
        for (Map.Entry<String, AttributeValue> entry : foodMap.entrySet()) {
            String foodName = entry.getKey();
            AttributeValue quantityAttr = entry.getValue();
            if (isFruit(foodName)) {
                appendFruit(promptBuilder, foodName, quantityAttr);
            } else {
                appendOtherIngredient(promptBuilder, foodName, quantityAttr);
            }
        }
    }

    private void appendFruit(StringBuilder promptBuilder, String foodName, AttributeValue quantityAttr) {
        int foodQuantity = Integer.parseInt(quantityAttr.s());
        if (foodQuantity != 0) {
            promptBuilder.append(String.format(", %d units of %s", foodQuantity, foodName));
        }
    }

    private void appendOtherIngredient(StringBuilder promptBuilder, String foodName, AttributeValue quantityAttr) {
        if (quantityAttr.n() != null) {
            int foodQuantity = Integer.parseInt(quantityAttr.n());
            if (foodQuantity != 0) {
                promptBuilder.append(String.format(", %dg of %s", foodQuantity, foodName));
            }
        } else if (quantityAttr.s() != null) {
            String foodQuantityString = quantityAttr.s();
            if (!"0".equals(foodQuantityString)) {
                promptBuilder.append(String.format(", %s %s", foodQuantityString, foodName));
            }
        }
    }

    private boolean isFruit(String foodName) {
        return fruitUnits.contains(foodName);
    }

    private void appendAllergens(StringBuilder promptBuilder, Map<String, AttributeValue> userData) {
        List<AttributeValue> allergiesList = Optional.ofNullable(userData.get("allergies-intolerances"))
                .map(AttributeValue::l).orElse(Collections.emptyList());
        if (!allergiesList.isEmpty()) {
            promptBuilder.append(", avoiding ingredients such as");
            for (AttributeValue allergy : allergiesList) {
                promptBuilder.append(" ").append(allergy.s()).append(",");
            }
            promptBuilder.deleteCharAt(promptBuilder.length() - 1);
        }
    }

    private void appendDietaryPreferences(StringBuilder promptBuilder, Map<String, AttributeValue> userData, boolean vegan, boolean vegetarian) {
        boolean userIsVegan = userData.get("vegan").bool();
        boolean userIsVegetarian = userData.get("vegetarian").bool();
        if (userIsVegan || vegan) {
            promptBuilder.append(", and ensuring it is vegan-friendly");
        } else if (userIsVegetarian || vegetarian) {
            promptBuilder.append(", and ensuring it is vegetarian-friendly");
        }
    }

    private void appendDietType(StringBuilder promptBuilder, Map<String, AttributeValue> userData) {
        String dietType = userData.get("dietType").s();
        if (dietType != null && !dietType.isEmpty()) {
            promptBuilder.append(String.format(", ensuring it fits %s diet", dietType));
        }
    }

    private void appendAvailableEquipment(StringBuilder promptBuilder, Map<String, AttributeValue> userData) {
        List<AttributeValue> equipmentList = Optional.ofNullable(userData.get("equipment"))
                .map(AttributeValue::l).orElse(Collections.emptyList());
        if (!equipmentList.isEmpty()) {
            promptBuilder.append(", with available equipment: ");
            for (AttributeValue equipment : equipmentList) {
                promptBuilder.append(" ").append(equipment.s()).append(",");
            }
            promptBuilder.deleteCharAt(promptBuilder.length() - 1);
        }
    }

    private Map<String, Object> buildRequestBody(String prompt) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", OPENAI_MODEL);
        requestBody.put("messages", Arrays.asList(
            Map.of("role", "system", "content", generateSystemInstructions()),
            Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("max_tokens", MODEL_MAX_TOKENS);
        requestBody.put("temperature", MODEL_TEMPERATURE);
        return requestBody;
    }

    private ChatCompletionResponse callOpenAI(Map<String, Object> requestBody) {
        return webClient.post()
            .uri(URL)
            .headers(httpHeaders -> {
                httpHeaders.setContentType(MediaType.APPLICATION_JSON);
                httpHeaders.setBearerAuth(OPENAI_AI_KEY);
            })
            .bodyValue(objectMapper.writeValueAsString(requestBody))
            .exchangeToMono(clientResponse -> {
                if (clientResponse.statusCode().is2xxSuccessful()) {
                    return clientResponse.bodyToMono(ChatCompletionResponse.class);
                } else {
                    clientResponse.bodyToMono(String.class)
                        .doOnNext(responseBody -> System.out.println("OpenAI API Error: " + responseBody))
                        .subscribe();
                    return Mono.error(new RuntimeException("Error occurred while generating wordage"));
                }
            })
            .block();
    }

    private void putItemInDynamoDB(String opId, String openAIResult) {
        Map<String, AttributeValue> itemAttributes = new HashMap<>();
        itemAttributes.put("opId", AttributeValue.builder().s(opId).build());
        itemAttributes.put("openAIResult", AttributeValue.builder().s(parseJson(openAIResult)).build());
        itemAttributes.put("ttl", AttributeValue.builder().n(String.valueOf(System.currentTimeMillis() / 1000L + 5 * 60)).build());

        PutItemRequest request = PutItemRequest.builder()
            .tableName(RESULT_TABLE_NAME)
            .item(itemAttributes)
            .build();

        dynamoDbClient.putItem(request);
    }

    private String parseJson(String openAIResult) {
        int startIndex = openAIResult.indexOf('{');
        int endIndex = openAIResult.lastIndexOf('}');
        if (startIndex != -1 && endIndex != -1) {
            return openAIResult.substring(startIndex, endIndex + 1);
        } else {
            throw new RuntimeException("Invalid JSON string format generated by OpenAI");
        }
    }

    private Map<String, Object> buildSuccessResponse(String result) {
        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("statusCode", 200);
        responseBody.put("body", result);
        return responseBody;
    }

    private String buildErrorResponse(String errorMessage) {
        return "Error occurred: " + errorMessage;
    }

    private String generateSystemInstructions() {
        return "You are a helpful assistant that generates a JSON response following this structure: {\n"
            + "  \"recipeName\": \"\",\n"
            + "  \"cookingTime\": \"\",\n"
            + "  \"caloriesAndMacros\": {\n"
            + "    \"calories\": \"\",\n"
            + "    \"protein\": \"\",\n"
            + "    \"carbs\": \"\",\n"
            + "    \"fat\": \"\"\n"
            + "  },\n"
            + "  \"ingredientsAndQuantities\": {\n"
            + "    \"ingredient name\": \"\", \"ingredient quantity\": \"\",\n"
            + "    \"ingredient name\": \"\", \"ingredient quantity\": \"\"\n"
            + "  },\n"
            + "  \"cookingProcess\": [\n"
            + "    \"Step 1\",\n"
            + "    \"Step 2\"\n"
            + "  ]\n"
            + "}. Ensure that the provided ingredients and quantities exactly fit the provided calories and macros.";
    }
}
