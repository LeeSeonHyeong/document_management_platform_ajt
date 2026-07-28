import { readFileSync } from "node:fs";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";

const currentDir = dirname(fileURLToPath(import.meta.url));
const publicPath = `${currentDir}/AJT-Backend-Public-API.postman_collection.json`;
const internalPath = `${currentDir}/AJT-FastAPI-Internal-API.postman_collection.json`;
const environmentPath = `${currentDir}/AJT-Local.postman_environment.json`;

const publicCollection = JSON.parse(readFileSync(publicPath, "utf8"));
const internalCollection = JSON.parse(readFileSync(internalPath, "utf8"));
const environment = JSON.parse(readFileSync(environmentPath, "utf8"));

const requiredDescriptionSections = [
  "### Authorization",
  "### Path Params",
  "### Query Params",
  "### Request Body",
  "### 정책",
  "### Response",
  "### Error",
];

function flattenRequests(collection) {
  return collection.item.flatMap((folder) =>
    folder.item.map((item) => ({ folder: folder.name, ...item })),
  );
}

function validateCollection(collection, expectedPathPrefix) {
  const errors = [];
  const requests = flattenRequests(collection);

  if (
    collection.info.schema !==
    "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
  ) {
    errors.push(`${collection.info.name}: Collection v2.1 schema URL 누락`);
  }

  for (const item of requests) {
    const description = item.request.description ?? "";
    const rawUrl = item.request.url?.raw ?? "";

    for (const section of requiredDescriptionSections) {
      if (!description.includes(section)) {
        errors.push(`${item.folder}/${item.name}: ${section} 누락`);
      }
    }

    if (!rawUrl.includes(expectedPathPrefix)) {
      errors.push(
        `${item.folder}/${item.name}: 경로가 ${expectedPathPrefix} 경계에 속하지 않음`,
      );
    }
    if (!item.request.method) {
      errors.push(`${item.folder}/${item.name}: HTTP Method 누락`);
    }
  }

  return { errors, requestCount: requests.length };
}

const publicResult = validateCollection(publicCollection, "/api/v1/");
const internalResult = validateCollection(internalCollection, "/internal/v1/");
const environmentKeys = new Set(environment.values.map((value) => value.key));
const requiredEnvironmentKeys = [
  "backendBaseUrl",
  "aiBaseUrl",
  "csrfToken",
  "internalApiKey",
];

const environmentErrors = requiredEnvironmentKeys
  .filter((key) => !environmentKeys.has(key))
  .map((key) => `Environment 변수 누락: ${key}`);

const errors = [
  ...publicResult.errors,
  ...internalResult.errors,
  ...environmentErrors,
];

console.log(
  JSON.stringify(
    {
      publicRequests: publicResult.requestCount,
      internalRequests: internalResult.requestCount,
      environmentVariables: [...environmentKeys],
      errors,
    },
    null,
    2,
  ),
);

if (errors.length) {
  process.exitCode = 1;
}
