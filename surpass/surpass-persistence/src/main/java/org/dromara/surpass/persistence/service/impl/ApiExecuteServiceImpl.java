package org.dromara.surpass.persistence.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.dromara.mybatis.jpa.datasource.DataSourceSwitch;
import org.dromara.mybatis.jpa.entity.JpaPage;
import org.dromara.surpass.entity.ApiRequestUri;
import org.dromara.surpass.entity.api.ApiParam;
import org.dromara.surpass.entity.api.ApiParamRule;
import org.dromara.surpass.entity.api.ApiVersion;
import org.dromara.surpass.entity.api.dto.ApiParamList;
import org.dromara.surpass.entity.app.AppResources;
import org.dromara.surpass.enums.ApiFieldDataTypeEnum;
import org.dromara.surpass.enums.ApiSupportPagingEnum;
import org.dromara.surpass.exception.BusinessException;
import org.dromara.surpass.persistence.service.ApiDataSourceService;
import org.dromara.surpass.persistence.service.ApiExecuteService;
import org.dromara.surpass.persistence.service.ApiVersionService;
import org.dromara.surpass.persistence.service.AppResourcesService;
import org.dromara.surpass.persistence.service.ISqlRepository;
import org.dromara.surpass.util.JsonUtils;
import org.springframework.stereotype.Service;

import java.util.*;


@Slf4j
@Service
@RequiredArgsConstructor
public class ApiExecuteServiceImpl implements ApiExecuteService {
    private static final String DEFAULT_PAGE_NUM_KEY = "_pageNum";
    private static final String DEFAULT_PAGE_SIZE_KEY = "_pageSize";

    private final ISqlRepository sqlRepository;

    private final ApiVersionService apiVersionService;

    private final AppResourcesService appResourcesService;

    private final ApiDataSourceService dataSourceService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 动态执行API
     *
     * @param apiRequestUri 请求路径信息
     * @param method        请求方法
     * @param params        参数
     * @param isTest        测试
     * @return 执行结果
     */
    public Object execute(ApiRequestUri apiRequestUri, String method, Map<String, Object> params, boolean isTest) {
        try {
            AppResources byPathAndMethod;
            ApiVersion apiVersion;
            if (isTest) {
                apiVersion = getApiVersion(params);
                byPathAndMethod = appResourcesService.get(apiVersion.getApiId());
                params = getRequestParams(params);
            } else {
                byPathAndMethod = appResourcesService.findByPathAndMethod(apiRequestUri.getResourcePath(), method, apiRequestUri.getContextPath());
                if (Objects.isNull(byPathAndMethod)) {
                    throw new BusinessException(50001, "API不存在");
                }
                apiVersion = apiVersionService.findPublishedVersionByApiId(byPathAndMethod.getId());
                if (Objects.isNull(apiVersion)) {
                    throw new BusinessException(50001, "API未发布");
                }
            }

            // 3、参数验证和转换
            Map<String, Object> parseredParams = validateAndConvert(params, apiVersion.getParamDefinition());
            // 4. 切换数据源
            dataSourceService.switchDataSource(byPathAndMethod.getDatasourceId());
            // 5. 获取SQL
            String sql = apiVersion.getSqlTemplate().trim();
            // 判断是否分页
            if (ApiSupportPagingEnum.PAGE.getCode().equals(apiVersion.getSupportsPaging())) {
                int pageNum = Integer.parseInt(params.getOrDefault(DEFAULT_PAGE_NUM_KEY, "1").toString());
                int pageSize = Integer.parseInt(params.getOrDefault(DEFAULT_PAGE_SIZE_KEY, "20").toString());
                JpaPage page = new JpaPage(pageNum, pageSize);
                return sqlRepository.fetch(sql, page, parseredParams);
            } else if (ApiSupportPagingEnum.LIST.getCode().equals(apiVersion.getSupportsPaging())) {
                // 查询操作
                return sqlRepository.selectList(sql, parseredParams);
            } else if (ApiSupportPagingEnum.SINGLE.getCode().equals(apiVersion.getSupportsPaging())) {
                // 查询操作
                JpaPage page = new JpaPage(1, 1);
                List<Map<String, Object>> rows = sqlRepository.fetch(sql, page, parseredParams).getRows();
                if (!rows.isEmpty()) {
                    return rows.get(0);
                }
                return null;
            } else if (ApiSupportPagingEnum.INSERT.getCode().equals(apiVersion.getSupportsPaging())) {
                // 插入操作
                int generatedKey = sqlRepository.insert(sql, parseredParams);
                return Map.of("affectedRows", 1, "generatedKey", generatedKey);
            } else if (ApiSupportPagingEnum.UPDATE.getCode().equals(apiVersion.getSupportsPaging())) {
                // 更新
                int affectedRows = sqlRepository.update(sql, parseredParams);
                return Map.of("affectedRows", affectedRows);
            } else if (ApiSupportPagingEnum.DELETE.getCode().equals(apiVersion.getSupportsPaging())) {
                // 删除操作
                int affectedRows = sqlRepository.delete(sql, parseredParams);
                return Map.of("affectedRows", affectedRows);
            } else {
                throw new BusinessException(50001, "不支持的SQL类型: " + sql);
            }

        } catch (Exception e) {
            log.error("执行API失败: {} {}", method, apiRequestUri.getRequestPath(), e);
            throw new BusinessException(50001, "API执行失败: " + e.getMessage());
        } finally {
            DataSourceSwitch.switchToDefault();
        }
    }

    /**
     * 参数验证和转换
     *
     * @param params        参数
     * @param paramRuleJson 参数规则定义
     */
    public Map<String, Object> validateAndConvert(Map<String, Object> params, String paramRuleJson) {
        log.debug("params : {}", params);
        log.debug("paramJson : {}", paramRuleJson);
        Map<String, Object> convertedParams = new HashMap<>();
        String json = "{\"paramList\":" + paramRuleJson.replace("\n", "") + "}";
        ApiParamList paramList = JsonUtils.stringToObject(json, ApiParamList.class);
        log.debug("paramList : {}", paramList);
        for (ApiParam apiParam : paramList.getParamList()) {
            Object value = params.get(apiParam.getName());
            if (value != null) {
                ApiFieldDataTypeEnum typeEnum =
                        ApiFieldDataTypeEnum.fromCode(apiParam.getType());
                Object convertedValue;
                if (typeEnum != null) {
                    convertedValue = typeEnum.convert(value);
                } else {
                    // 兜底策略：未知类型直接使用原值
                    convertedValue = value;
                }
                // 参数校验
                paramCheck(convertedValue, apiParam.getRules());
                convertedParams.put(apiParam.getName(), convertedValue);
            } else {
                paramCheck(null, apiParam.getRules());
                if (apiParam.getRules().getDefaultValue() != null) {
                    convertedParams.put(apiParam.getName(), apiParam.getRules().getDefaultValue());
                }
            }
        }
        log.debug("converted Params : {}", convertedParams);
        return convertedParams;
    }

    private ApiVersion getApiVersion(Map<String, Object> params) throws JsonProcessingException {
        return objectMapper.readValue(params.get("apiVersion").toString(), ApiVersion.class);
    }

    private Map<String, Object> getRequestParams(Map<String, Object> params) {
        return (Map<String, Object>) params.getOrDefault("data", new HashMap<String, Object>() {
        });
    }

    /**
     * 请求参数校验
     *
     * @param convertedValue 转换后的参数值
     * @param rules          规则定义
     */
    private void paramCheck(Object convertedValue, ApiParamRule rules) {
        if (rules == null) {
            return;
        }

        // 必填校验
        if (rules.isRequired() && convertedValue == null) {
            throw new BusinessException(40001, "参数不能为空");
        }

        // 如果值为空，不进行其他校验
        if (convertedValue == null) {
            return;
        }

        String valueStr = convertedValue.toString();

        // 字符串长度校验
        if (rules.getMinLength() != null || rules.getMaxLength() != null) {
            int length = valueStr.length();
            if (rules.getMinLength() != null && rules.getMinLength() > 0 && length < rules.getMinLength()) {
                throw new BusinessException(40002, "参数长度不能小于" + rules.getMinLength());
            }
            if (rules.getMaxLength() != null && rules.getMaxLength() > 0 && length > rules.getMaxLength()) {
                throw new BusinessException(40003, "参数长度不能大于" + rules.getMaxLength());
            }
        }

        // 数值范围校验
        if (rules.getMinValue() != null || rules.getMaxValue() != null) {
            try {
                long value = Long.parseLong(valueStr);
                if (rules.getMinValue() != null && value < rules.getMinValue()) {
                    throw new BusinessException(40004, "参数值不能小于" + rules.getMinValue());
                }
                if (rules.getMaxValue() != null && value > rules.getMaxValue()) {
                    throw new BusinessException(40005, "参数值不能大于" + rules.getMaxValue());
                }
            } catch (NumberFormatException e) {
                throw new BusinessException(40006, "参数值不是有效的数字");
            }
        }

        // 正则表达式校验
        if (rules.getPattern() != null && !rules.getPattern().isEmpty()) {
            if (!valueStr.matches(rules.getPattern())) {
                throw new BusinessException(40007, "参数格式不符合要求");
            }
        }

        // 枚举值校验
        if (rules.getEnumValues() != null && !rules.getEnumValues().isEmpty()) {
            String[] enumValues = rules.getEnumValues().split(",");
            boolean found = false;
            for (String enumValue : enumValues) {
                if (enumValue.trim().equals(valueStr)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new BusinessException(40008, "参数值必须是: " + rules.getEnumValues());
            }
        }

        // 格式校验（日期、邮箱等）
        if (rules.getFormat() != null && !rules.getFormat().isEmpty()) {
            validateFormat(valueStr, rules.getFormat());
        }
    }

    /**
     * 格式校验
     */
    private void validateFormat(String value, String format) {
        switch (format.toLowerCase()) {
            case "email":
                if (!value.matches("^[A-Za-z0-9+_.-]+@(.+)$")) {
                    throw new BusinessException(40009, "邮箱格式不正确");
                }
                break;
            case "phone":
                if (!value.matches("^1[3-9]\\d{9}$")) {
                    throw new BusinessException(40010, "手机号格式不正确");
                }
                break;
            case "date":
                if (!value.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                    throw new BusinessException(40011, "日期格式必须是YYYY-MM-DD");
                }
                break;
            case "datetime":
                if (!value.matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$")) {
                    throw new BusinessException(40012, "日期时间格式必须是YYYY-MM-DD HH:mm:ss");
                }
                break;
            case "url":
                if (!value.matches("^(https?|ftp)://[^\\s/$.?#].[^\\s]*$")) {
                    throw new BusinessException(40013, "URL格式不正确");
                }
                break;
            case "ip":
                if (!value.matches("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")) {
                    throw new BusinessException(40014, "IP地址格式不正确");
                }
                break;
            default:
                // 未知格式，不校验
                break;
        }
    }
}
