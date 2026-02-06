package org.dromara.surpass.web.api.controller;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.dromara.mybatis.jpa.query.LambdaQuery;
import org.dromara.surpass.entity.api.ApiParam;
import org.dromara.surpass.entity.api.ApiParamRule;
import org.dromara.surpass.entity.api.ApiVersion;
import org.dromara.surpass.entity.api.dto.ApiParamList;
import org.dromara.surpass.entity.app.App;
import org.dromara.surpass.entity.app.AppResources;
import org.dromara.surpass.persistence.service.ApiVersionService;
import org.dromara.surpass.persistence.service.AppResourcesService;
import org.dromara.surpass.persistence.service.AppService;
import org.dromara.surpass.util.JsonUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 动态API文档控制器，提供符合SpringDoc规范的API文档接口
 * 完全按照springdoc-api-res-demo.json的结构自定义响应
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v3/api-docs")
public class SwaggerApiController {

    private final AppResourcesService appResourcesService;
    private final AppService appService;
    private final ApiVersionService apiVersionService;

    @Value("${server.port:2154}")
    private String serverPort;

    @Value("${server.servlet.context-path:/surpass-api}")
    private String contextPath;

    @Value("${application.title:Surpass}")
    private String applicationTitle;

    @Value("${application.formatted-version:v1.2.0 GA}")
    private String applicationVersion;

    /**
     * 返回所有已发布动态API的OpenAPI文档定义
     * 完全按照springdoc-api-res-demo.json的结构
     */
    @GetMapping(value = "/dynamic-apis")
    public Object getDynamicApiDocs(HttpServletRequest request) {
        ObjectMapper jsonMapper = new ObjectMapper();
        jsonMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        OpenAPI openAPI = new OpenAPI();
        // 2. 设置基本信息 - 与demo文件格式一致
        Info info = new Info()
                .title(applicationTitle)
                .description(applicationTitle + "API文档")
                .version(applicationVersion);

        Contact contact = new Contact()
                .name("Surpass")
                .email("Surpass@Surpass.com");
        info.setContact(contact);

        openAPI.setInfo(info);

        // 3. 添加服务器信息 - 与demo文件格式一致
        List<Server> servers = new ArrayList<>();
        Server server = new Server();
        server.setUrl("http://127.0.0.1:" + serverPort + contextPath + "/api");
        server.setDescription("测试服务");
        servers.add(server);
        openAPI.setServers(servers);

        // 4. 设置安全方案 - 与demo文件一致
        List<SecurityRequirement> securityRequirements = new ArrayList<>();
        SecurityRequirement securityRequirement = new SecurityRequirement();
        securityRequirement.addList("apiKey");
        securityRequirements.add(securityRequirement);
        openAPI.setSecurity(securityRequirements);

        // 5. 添加标签 - 使用动态API标签
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag().name("动态API").description("动态生成的API接口"));
        openAPI.setTags(tags);

        // 6. 构建路径信息 - 只包含动态API
        Paths paths = buildDynamicApiPaths();
        openAPI.setPaths(paths);

        // 7. 设置组件 - 完全按照demo文件的结构
        Components components = new Components();
        SecurityScheme apiKey = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .name("maxkey")
                .in(SecurityScheme.In.HEADER);
        components.addSecuritySchemes("apiKey", apiKey);
        addDemoSchemas(components);
        openAPI.setComponents(components);

        try {
            String resStr = jsonMapper.writeValueAsString(openAPI);
            return jsonMapper.readTree(resStr);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 添加demo文件中的Schema定义
     */
    private void addDemoSchemas(Components components) {
        ObjectSchema ApiKey = new ObjectSchema();
        ApiKey.addProperty("maxkey", new StringSchema());
        ApiKey.addProperty("apiKey", new StringSchema());
        components.addSchemas("ApiKey", ApiKey);
    }

    /**
     * 构建动态API的paths
     */
    private Paths buildDynamicApiPaths() {
        Paths paths = new Paths();
        List<App> appList = appService.findAll();
        Map<String, App> appMap = new HashMap<>();
        for (App app : appList) {
            appMap.put(app.getId(), app);
        }

        // 获取所有API定义
        LambdaQuery<AppResources> wrapper = new LambdaQuery<>();
        wrapper.eq(AppResources::getClassify, "openApi");
        List<AppResources> apiDefinitions = appResourcesService.query(wrapper);

        for (AppResources apiDefinition : apiDefinitions) {
            // 获取已发布的版本
            ApiVersion publishedVersion = apiVersionService.findPublishedVersionByApiId(apiDefinition.getId());
            if (publishedVersion != null) {
                // 构建路径项
                io.swagger.v3.oas.models.PathItem pathItem = buildPathItem(apiDefinition, publishedVersion);
                paths.addPathItem(appMap.get(apiDefinition.getAppId()).getContextPath() + apiDefinition.getPath(), pathItem);
            }
        }

        return paths;
    }

    /**
     * 构建单个路径项
     */
    private io.swagger.v3.oas.models.PathItem buildPathItem(AppResources apiDefinition, ApiVersion apiVersion) {
        io.swagger.v3.oas.models.PathItem pathItem = new io.swagger.v3.oas.models.PathItem();
        // 构建操作
        io.swagger.v3.oas.models.Operation operation = new io.swagger.v3.oas.models.Operation();
        operation.setOperationId("dynamic_" + apiDefinition.getId() + "_" + apiDefinition.getMethod().toLowerCase());
        operation.setSummary(apiDefinition.getName());
        operation.setDescription(apiDefinition.getDescription());
        operation.addTagsItem(apiDefinition.getMethod().toLowerCase() + "_" + apiVersion.getApiId());

        // 构建响应 - 使用Message Schema
        ApiResponses responses = new ApiResponses();
        ApiResponse successResponse = new ApiResponse();
        successResponse.setDescription("OK");

        Content content = new Content();
        MediaType mediaType = new MediaType();

        // 使用Message Schema
//        Schema<?> messageSchema = new Schema<>().$ref("#/components/schemas/Message");
//        mediaType.setSchema(messageSchema);
        content.addMediaType("application/json", mediaType);
        successResponse.setContent(content);

        responses.addApiResponse("200", successResponse);
        operation.setResponses(responses);

        // 构建参数
        List<Parameter> parameters = buildParameters(apiDefinition, apiVersion);
        if (!parameters.isEmpty()) {
            operation.setParameters(parameters);
        }

        // 设置HTTP方法
        String method = apiDefinition.getMethod().toUpperCase();
        switch (method) {
            case "GET":
                pathItem.setGet(operation);
                break;
            case "POST":
                pathItem.setPost(operation);
                // 为POST方法添加请求体
                operation.setRequestBody(buildRequestBody());
                break;
            case "PUT":
                pathItem.setPut(operation);
                // 为PUT方法添加请求体
                operation.setRequestBody(buildRequestBody());
                break;
            case "DELETE":
                pathItem.setDelete(operation);
                break;
        }

        return pathItem;
    }

    /**
     * 构建参数列表
     */
    private List<Parameter> buildParameters(AppResources apiDefinition, ApiVersion apiVersion) {
        List<Parameter> parameters = new ArrayList<>();
        String json = "{\"paramList\":" + apiVersion.getParamDefinition().replace("\n", "") + "}";
        ApiParamList paramList = JsonUtils.stringToObject(json, ApiParamList.class);
        for (ApiParam apiParam : paramList.getParamList()) {
            String in = "POST".equalsIgnoreCase(apiDefinition.getMethod()) || "PUT".equalsIgnoreCase(apiDefinition.getMethod()) ? "body" : "query";
            ApiParamRule rules = apiParam.getRules();
            PathParameter pathParam = new PathParameter();
            pathParam.setName(apiParam.getName());
            pathParam.setRequired(rules.isRequired());
            pathParam.setIn(in);
            pathParam.setDescription(apiParam.getDescription());
            pathParam.setSchema(new StringSchema());
            parameters.add(pathParam);
        }

        return parameters;
    }

    /**
     * 构建请求体
     */
    private io.swagger.v3.oas.models.parameters.RequestBody buildRequestBody() {
        io.swagger.v3.oas.models.parameters.RequestBody requestBody = new io.swagger.v3.oas.models.parameters.RequestBody();
        requestBody.setRequired(true);

        Content content = new Content();
        MediaType mediaType = new MediaType();

        // 使用ObjectSchema作为默认请求体
        mediaType.setSchema(new ObjectSchema().$ref("#/components/schemas/ApiKey"));
        content.addMediaType("application/json", mediaType);
        requestBody.setContent(content);

        return requestBody;
    }
}
