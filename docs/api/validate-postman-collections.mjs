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
const csrfProtectedMethods = new Set(["POST", "PUT", "PATCH", "DELETE"]);

function flattenRequests(collection) {
  return collection.item.flatMap((folder) =>
    folder.item.map((item) => ({ folder: folder.name, ...item })),
  );
}

function findRequest(collection, method, path) {
  return flattenRequests(collection).find(
    (item) =>
      item.request.method === method &&
      item.request.url?.raw?.endsWith(path),
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
    if (
      expectedPathPrefix === "/api/v1/" &&
      csrfProtectedMethods.has(item.request.method)
    ) {
      const hasCsrfHeader = (item.request.header ?? []).some(
        (header) =>
          header.key?.toLowerCase() === "x-xsrf-token" &&
          header.value === "{{csrfToken}}",
      );
      if (!hasCsrfHeader) {
        errors.push(`${item.folder}/${item.name}: X-XSRF-TOKEN 헤더 누락`);
      }
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

const wikiEditRequest = findRequest(
  internalCollection,
  "POST",
  "/internal/v1/wiki-edits",
);
const wikiEditBody = wikiEditRequest?.request.body?.raw
  ? JSON.parse(wikiEditRequest.request.body.raw)
  : {};
const wikiEditErrors = [];
if (wikiEditBody.adminInstructionDocumentId !== "817") {
  wikiEditErrors.push(
    "Wiki 관리자 수정: adminInstructionDocumentId 필수 문자열 예시 누락",
  );
}
if (
  !wikiEditRequest?.request.description?.includes("adminInstructionDocumentId")
) {
  wikiEditErrors.push(
    "Wiki 관리자 수정: adminInstructionDocumentId 요청 설명 누락",
  );
}

const errors = [
  ...publicResult.errors,
  ...internalResult.errors,
  ...environmentErrors,
  ...wikiEditErrors,
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
