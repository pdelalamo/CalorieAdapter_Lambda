# OpenAILambda - AWS Lambda Function

## Overview

The `OpenAILambda` function is a serverless application designed to interact with the OpenAI API to generate customized recipes based on user preferences and dietary requirements. It retrieves user data from DynamoDB, constructs a prompt for OpenAI, sends the request, and stores the result back into DynamoDB. This function is part of the `FitMyMacros` application, providing personalized meal suggestions based on macro targets and dietary restrictions.

## Features

- **Recipe Customization**: Generates recipes based on user-defined calories, macros, dietary preferences (e.g., vegan, gluten-free), and available ingredients.
- **DynamoDB Integration**: Retrieves user-specific data (ingredients, allergies, equipment) and stores the OpenAI response.
- **SSM Parameter Store Integration**: Securely fetches sensitive API keys and model configuration from AWS SSM Parameter Store.
- **Error Handling**: Handles errors from the OpenAI API and DynamoDB with appropriate logging and responses.

## Architecture

1. **Input**: 
   - The Lambda function accepts a JSON payload with query parameters such as `recipeName`, `userId`, `calories`, `protein`, `carbs`, `fat`, etc.
   - Example input:
     ```json
     {
         "queryStringParameters": {
             "querystring": "recipeName=Pasta, userId=123, measureUnit=g, calories=500, protein=25, carbs=60, fat=20, glutenFree=true, vegan=false, cookingTime=30, anyIngredientsMode=false"
         }
     }
     ```

2. **Processing**:
   - **SSM Parameter Store**: Retrieves API key and model configurations.
   - **DynamoDB**: Fetches user data such as available ingredients, allergies, and equipment.
   - **Prompt Generation**: Constructs a customized prompt based on the input parameters and user data.
   - **OpenAI API Call**: Sends the prompt to OpenAI's API and retrieves the response.
   - **DynamoDB Storage**: Stores the result in DynamoDB with a TTL of 5 minutes.

3. **Output**: 
   - The Lambda function returns a JSON response containing the customized recipe or an error message.

## Deployment

1. **AWS SSM Parameter Store**: Ensure that the following parameters are securely stored in the AWS Parameter Store:
   - `OpenAI-API_Key_Encrypted`: Encrypted API key for OpenAI.
   - `OpenAI-Model-CalorieAdapter`: OpenAI model to be used for generating recipes.
   - `OpenAI-Model-Temperature-CalorieAdapter`: Temperature setting for the model.
   - `OpenAI-Max-Tokens`: Maximum number of tokens for the API response.

2. **AWS DynamoDB**: Ensure the following tables exist:
   - `FitMyMacros`: Contains user data including ingredients, allergies, and equipment.
   - `FitMyMacros_OpenAI_Results`: Stores the OpenAI API results with a TTL attribute.

3. **IAM Role**: The Lambda function should have appropriate permissions to access SSM Parameter Store and DynamoDB.

4. **Lambda Environment**:
   - Set up the Lambda function with appropriate environment variables (if any).
   - Ensure the function is configured with sufficient memory and timeout settings.

## Code Explanation

### Core Methods:

- **handleRequest**: Entry point for the Lambda function. Processes input, interacts with DynamoDB and OpenAI API, and returns the final response.
- **extractQueryString**: Extracts and parses query parameters from the input payload.
- **getOpenAIKeyFromParameterStore**: Retrieves the OpenAI API key from AWS SSM Parameter Store.
- **generatePrompt**: Constructs the OpenAI prompt based on user data and input parameters.
- **createPrompt**: Builds the detailed prompt, considering user-specific data such as available ingredients and dietary restrictions.
- **putItemInDynamoDB**: Stores the OpenAI result in DynamoDB with a TTL.

### Exception Handling:
- Handles exceptions related to DynamoDB and OpenAI API calls, ensuring errors are logged and appropriate responses are returned.
