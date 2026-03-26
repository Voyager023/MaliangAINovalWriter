package com.ainovel.server.service.impl;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

import com.ainovel.server.repository.AIPromptPresetRepository;

import org.apache.skywalking.apm.toolkit.trace.Trace;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ainovel.server.domain.model.Scene;

import com.ainovel.server.domain.model.Novel;
import com.ainovel.server.domain.model.AIPromptPreset;
import com.ainovel.server.domain.model.AIFeatureType;
import com.ainovel.server.domain.model.AIRequest;
import com.ainovel.server.domain.model.AIResponse;
import com.ainovel.server.domain.model.NovelSettingItem;
import com.ainovel.server.service.UniversalAIService;
import com.ainovel.server.service.NovelAIService;
import com.ainovel.server.service.NovelService;
import com.ainovel.server.service.SceneService;
import com.ainovel.server.service.NovelSettingService;
import com.ainovel.server.service.setting.SettingConversionService;
import com.ainovel.server.service.setting.generation.InMemorySessionManager;
import com.ainovel.server.service.UserPromptService;
import com.ainovel.server.service.cache.NovelStructureCache;
import com.ainovel.server.service.UserAIModelConfigService;
import com.ainovel.server.service.rag.RagService;
import com.ainovel.server.service.NovelSnippetService;
import com.ainovel.server.service.CreditService;
import com.ainovel.server.service.PublicModelConfigService;
import com.ainovel.server.repository.AIChatMessageRepository;
import com.ainovel.server.domain.model.AIChatMessage;
import com.ainovel.server.service.EnhancedUserPromptService;
import com.ainovel.server.web.dto.request.UniversalAIRequestDto;
import com.ainovel.server.web.dto.response.UniversalAIResponseDto;
import com.ainovel.server.web.dto.response.UniversalAIPreviewResponseDto;
import com.ainovel.server.common.util.RichTextUtil;
import com.ainovel.server.common.util.PromptXmlFormatter;

// 🚀 新增：导入重构后的内容提供器相关类
import com.ainovel.server.service.impl.content.ContentProviderFactory;
import com.ainovel.server.service.impl.content.ContentProvider;
import com.ainovel.server.service.impl.content.ContentResult;

// 🚀 新增：导入提示词提供器相关类
import com.ainovel.server.service.prompt.PromptProviderFactory;
import com.ainovel.server.service.prompt.AIFeaturePromptProvider;
import com.ainovel.server.service.prompt.impl.VirtualThreadPlaceholderResolver;
import com.ainovel.server.service.prompt.impl.ContextualPlaceholderResolver;
import com.ainovel.server.service.billing.BillingKeys;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

/**
 * 通用AI服务实现类
 * 位于最顶层，统一处理各种类型的AI请求
 * 负责数据获取、提示词组装和AI调用的协调
 */
@Slf4j
@Service
public class UniversalAIServiceImpl implements UniversalAIService {

    // 🚀 内容类型优先级常量（数字越小优先级越高）
    private static final int PRIORITY_FULL_NOVEL_TEXT = 1;
    private static final int PRIORITY_FULL_NOVEL_SUMMARY = 2;
    private static final int PRIORITY_ACT = 3;
    private static final int PRIORITY_CHAPTER = 4;
    private static final int PRIORITY_SCENE = 5;
    private static final int PRIORITY_SETTING = 6;
    private static final int PRIORITY_SNIPPET = 7;
    
    // 🚀 内容类型常量
    private static final String TYPE_FULL_NOVEL_TEXT = "full_novel_text";
    private static final String TYPE_FULL_NOVEL_SUMMARY = "full_novel_summary";
    private static final String TYPE_ACT = "act";
    private static final String TYPE_CHAPTER = "chapter";
    private static final String TYPE_SCENE = "scene";
    private static final String TYPE_CHARACTER = "character";
    private static final String TYPE_LOCATION = "location";
    private static final String TYPE_ITEM = "item";
    private static final String TYPE_LORE = "lore";
    private static final String TYPE_SNIPPET = "snippet";

    // 🚀 重构：使用ContentProviderFactory替代内部的contentProviders
    @Autowired
    private ContentProviderFactory contentProviderFactory;

    // 🚀 新增：提示词提供器工厂和占位符解析器
    @Autowired
    private PromptProviderFactory promptProviderFactory;
    
    @Autowired
    private VirtualThreadPlaceholderResolver placeholderResolver;

    @Autowired
    private NovelAIService novelAIService;

    @Autowired
    private NovelService novelService;

    @Autowired
    private SceneService sceneService;

    @Autowired
    private NovelSettingService novelSettingService;

    @Autowired
    private SettingConversionService settingConversionService;

    @Autowired
    private InMemorySessionManager inMemorySessionManager;

    @Autowired
    private EnhancedUserPromptService promptService;

    @Autowired
    private UserPromptService userPromptService;

    @Autowired
    private UserAIModelConfigService userAIModelConfigService;

    @Autowired
    private RagService ragService;

    @Autowired
    private com.ainovel.server.service.ai.observability.TraceContextManager traceContextManager;


    @Autowired
    private PromptXmlFormatter promptXmlFormatter;

    @Autowired
    private NovelSnippetService novelSnippetService;

    // 🚀 新增：AIPromptPresetRepository依赖注入
    @Autowired
    private AIPromptPresetRepository promptPresetRepository;

    // 🚀 新增：积分服务和公共模型服务依赖
    @Autowired
    private CreditService creditService;

    // 记录估算交易用
    @Autowired
    private com.ainovel.server.repository.CreditTransactionRepository creditTransactionRepository;

    @Autowired
    private PublicModelConfigService publicModelConfigService;
    
    @Autowired
    private AIChatMessageRepository messageRepository;
    
    // 删除公共AI应用服务依赖：统一走 Provider 装饰器链

    // 🚀 新增：增强的用户提示词服务依赖
    @Autowired
    private EnhancedUserPromptService enhancedUserPromptService;

    // 🚀 移除：所有内部的ContentProvider相关代码已提取为独立类
    // ContentProvider接口、ContentResult类和各种Provider实现已移动到独立的包中

    /**
     * 🔥 构建初始的系统和用户消息（用于多轮对话场景）
     */
    @Override
    public Mono<List<UniversalAIRequestDto.MessageDto>> buildInitialMessages(UniversalAIRequestDto request) {
        log.info("构建初始消息: requestType={}, userId={}", request.getRequestType(), request.getUserId());
        
        return buildPrompts(request)
                .map(prompts -> {
                    List<UniversalAIRequestDto.MessageDto> messages = new ArrayList<>();
                    
                    // 第1条：系统消息
                    String systemPrompt = prompts.get("system");
                    if (systemPrompt != null && !systemPrompt.isEmpty()) {
                        messages.add(UniversalAIRequestDto.MessageDto.builder()
                                .role("system")
                                .content(systemPrompt)
                                .build());
                        log.info("✅ 第1条系统消息构建完成，长度={}", systemPrompt.length());
                    }
                    
                    // 第2条：用户消息
                    String userPrompt = prompts.get("user");
                    if (userPrompt != null && !userPrompt.isEmpty()) {
                        messages.add(UniversalAIRequestDto.MessageDto.builder()
                                .role("user")
                                .content(userPrompt)
                                .build());
                        log.info("✅ 第2条用户消息构建完成，长度={}", userPrompt.length());
                    }
                    
                    log.info("✅ 初始消息构建完成，总计{}条", messages.size());
                    return messages;
                })
                .doOnError(error -> log.error("构建初始消息失败: {}", error.getMessage(), error));
    }
    
    @Override
    public Mono<UniversalAIResponseDto> processRequest(UniversalAIRequestDto request) {
        log.info("处理通用AI请求 - 类型: {}, 用户ID: {}", request.getRequestType(), request.getUserId());

        return buildAIRequest(request)
                .flatMap(aiRequest -> {
                    // 根据请求类型调用相应的AI服务
                    return callAIService(aiRequest, request.getRequestType())
                            .map(aiResponse -> convertToResponseDto(aiResponse, request.getRequestType()));
                })
                .doOnSuccess(response -> log.info("通用AI请求完成 - ID: {}", response.getId()))
                .doOnError(error -> log.error("通用AI请求失败: {}", error.getMessage(), error));
    }

    @Override
    @Trace(operationName = "ai.universal.stream")
    public Flux<UniversalAIResponseDto> processStreamRequest(UniversalAIRequestDto request) {
        log.info("处理流式通用AI请求 - 类型: {}, 用户ID: {}", request.getRequestType(), request.getUserId());

        return buildAIRequest(request)
                .flatMapMany(aiRequest -> {
                    // 根据请求类型调用相应的流式AI服务
                    return callAIServiceStream(aiRequest, request.getRequestType())
                            .filter(this::isValidStreamContent) // 先过滤掉无效内容
                            .map(content -> convertToStreamResponseDto(content, request.getRequestType()));
                })
                .doOnComplete(() -> log.info("流式通用AI请求完成"))
                .doOnError(error -> log.error("流式通用AI请求失败: {}", error.getMessage(), error));
    }

    /**
     * 当为 NOVEL_COMPOSE 且请求中无 novelId 时，先创建一个草稿小说并写回 request
     */
    private Mono<UniversalAIRequestDto> ensureNovelIdIfNeeded(UniversalAIRequestDto request) {
        try {
            if (request == null) return Mono.empty();
            String type = request.getRequestType();
            boolean isCompose = false;
            try {
                isCompose = AIFeatureType.valueOf(type) == AIFeatureType.NOVEL_COMPOSE;
            } catch (Exception ignore) {}
            if (!isCompose || (request.getNovelId() != null && !request.getNovelId().isEmpty())) {
                return Mono.just(request);
            }

            // 创建草稿小说（最简字段）
            Novel draft = new Novel();
            Novel.Author author = Novel.Author.builder().id(request.getUserId()).username(request.getUserId()).build();
            draft.setAuthor(author);
            draft.setTitle("未命名小说");
            draft.setDescription("自动创建的草稿，用于写作编排");
            // 可在Novel实体上添加草稿标记字段；此处仅创建基本对象

            return novelService.createNovel(draft)
                    .map(created -> {
                        request.setNovelId(created.getId());
                        // 在metadata上打标记（供后续链路/日志分析）
                        if (request.getMetadata() != null) {
                            request.getMetadata().put("associatedDraft", true);
                        }
                        return request;
                    })
                    .onErrorResume(e -> {
                        log.warn("创建草稿小说失败，继续无novelId流程: {}", e.getMessage());
                        return Mono.just(request);
                    });
        } catch (Exception e) {
            log.warn("ensureNovelIdIfNeeded 异常: {}", e.getMessage());
            return Mono.just(request);
        }
    }

    @Override
    public Mono<UniversalAIPreviewResponseDto> previewRequest(UniversalAIRequestDto request) {
        log.info("🚀 预览通用AI请求 - 类型: {}, 用户ID: {}", request.getRequestType(), request.getUserId());

        AIFeatureType featureType = mapRequestTypeToFeatureType(request.getRequestType());
        log.info("映射的功能类型: {} -> {}", request.getRequestType(), featureType);

        // 获取对应的提示词提供器
        AIFeaturePromptProvider provider = promptProviderFactory.getProvider(featureType);
        if (provider == null) {
            log.error("未找到功能类型 {} 的提示词提供器", featureType);
            return Mono.error(new IllegalArgumentException("不支持的请求类型: " + request.getRequestType()));
        }

        // 🚀 使用统一的PromptProvider架构获取预览数据
        Mono<String> contextDataMono = getContextData(request).cache();
        
        return buildPromptParameters(request, contextDataMono)
                .flatMap(parameters -> {
                    log.debug("开始生成预览，参数数量: {}", parameters.size());

                    // 覆盖逻辑：若前端传入了自定义提示词，则优先使用
                    String customSystem = null;
                    String customUser = null;
                    Object cs = parameters.get("customSystemPrompt");
                    Object cu = parameters.get("customUserPrompt");
                    if (cs instanceof String && !((String) cs).isEmpty()) customSystem = (String) cs;
                    if (cu instanceof String && !((String) cu).isEmpty()) customUser = (String) cu;

                    Mono<String> systemMono = (customSystem != null)
                            ? Mono.just(customSystem)
                            : provider.getSystemPrompt(request.getUserId(), parameters)
                                .doOnNext(sp -> log.debug("系统提示词生成完成，长度: {}", sp != null ? sp.length() : 0));

                    Mono<String> userMono = (customUser != null)
                            ? Mono.just(customUser)
                            : provider.getUserPrompt(request.getUserId(), null, parameters)
                                .doOnNext(up -> log.debug("用户提示词生成完成，长度: {}", up != null ? up.length() : 0));

                    // 并行获取系统提示词和用户提示词
                    return Mono.zip(systemMono, userMono);
                })
                .map(tuple -> {
                    String systemPrompt = tuple.getT1();
                    String userPrompt = tuple.getT2();
                    
                    // 🚀 提取模型配置信息（简化版本）
                    String modelName = extractModelName(request);
                    String modelProvider = extractModelProvider(request);
                    String modelConfigId = extractModelConfigId(request);
                    
                    // 🚀 构建简化的预览内容（只包含系统提示词和用户提示词）
                    StringBuilder fullPreviewBuilder = new StringBuilder();
                    
                    if (systemPrompt != null && !systemPrompt.isEmpty()) {
                        fullPreviewBuilder.append("=== 系统提示词 ===\n").append(systemPrompt).append("\n\n");
                    }
                    
                    if (userPrompt != null && !userPrompt.isEmpty()) {
                        fullPreviewBuilder.append("=== 用户提示词 ===\n").append(userPrompt);
                    }
                    
                    String fullPreview = fullPreviewBuilder.toString().trim();
                    
                    log.info("预览生成完成 - 系统提示词: {}字符, 用户提示词: {}字符", 
                             systemPrompt.length(), userPrompt.length());

                    return UniversalAIPreviewResponseDto.builder()
                            .preview(fullPreview)
                            .systemPrompt(systemPrompt)
                            .userPrompt(userPrompt)
                            .context("") // 上下文返回空字符串
                            .estimatedTokens(estimateTokens(fullPreview))
                            .modelName(modelName)
                            .modelProvider(modelProvider)
                            .modelConfigId(modelConfigId)
                            .build();
                })
                .doOnSuccess(response -> log.info("🚀 通用AI预览完成 - 模型: {}, 估算tokens: {}, 功能类型: {}", 
                                                 response.getModelName(), response.getEstimatedTokens(), featureType))
                .doOnError(error -> log.error("通用AI预览失败: {}", error.getMessage(), error));
    }

    /**
     * 构建AI请求对象
     */
    @Trace(operationName = "ai.universal.buildAIRequest")
    @Override
    public Mono<AIRequest> buildAIRequest(UniversalAIRequestDto request) {
        return buildPrompts(request)
                .flatMap(prompts -> {
                    AIRequest aiRequest = new AIRequest();
                    aiRequest.setUserId(request.getUserId());
                    aiRequest.setNovelId(request.getNovelId());
                    aiRequest.setSceneId(request.getSceneId());
                    // 设置明确的功能类型
                    try {
                        AIFeatureType ft = mapRequestTypeToFeatureType(request.getRequestType());
                        aiRequest.setFeatureType(ft);
                    } catch (Exception ignore) {}

                    // 从多个来源获取模型配置信息
                    String modelName = null;
                    String modelProvider = null;
                    String modelConfigId = null;

                    // 1. 优先从直接字段获取
                    if (request.getModelConfigId() != null) {
                        modelConfigId = request.getModelConfigId();
                    }

                    // 2. 从元数据中获取
                    if (request.getMetadata() != null) {
                        Object modelNameObj = request.getMetadata().get("modelName");
                        Object modelProviderObj = request.getMetadata().get("modelProvider");
                        Object modelConfigIdObj = request.getMetadata().get("modelConfigId");

                        if (modelNameObj instanceof String) {
                            modelName = (String) modelNameObj;
                        }
                        if (modelProviderObj instanceof String) {
                            modelProvider = (String) modelProviderObj;
                        }
                        if (modelConfigIdObj instanceof String) {
                            modelConfigId = (String) modelConfigIdObj;
                        }
                    }

                    // 3. 从请求参数中获取（备用）
                    if (request.getParameters() != null) {
                        Object modelNameParam = request.getParameters().get("modelName");
                        if (modelNameParam instanceof String && modelName == null) {
                            modelName = (String) modelNameParam;
                        }
                    }

                    // 设置模型信息到AIRequest
                    if (modelName != null && !modelName.isEmpty()) {
                        aiRequest.setModel(modelName);
                        log.info("设置AI请求模型: {}", modelName);
                    }

                    // 设置模型参数
                    if (request.getParameters() != null) {
                        Object temperatureObj = request.getParameters().get("temperature");
                        Object maxTokensObj = request.getParameters().get("maxTokens");

                        if (temperatureObj instanceof Number) {
                            aiRequest.setTemperature(((Number) temperatureObj).doubleValue());
                        }
                        if (maxTokensObj instanceof Number) {
                            aiRequest.setMaxTokens(((Number) maxTokensObj).intValue());
                        }
                    }

                    // 设置系统提示词到prompt字段（用于LangChain4j等AI服务）
                    final String systemPrompt = prompts.get("system");
                    if (systemPrompt != null && !systemPrompt.isEmpty()) {
                        aiRequest.setPrompt(systemPrompt);
                    }

                    // 统一历史组装：AI_CHAT 且存在 sessionId 时，拼接最近历史 + 当前用户消息
                    final String userPrompt = prompts.get("user");
                    final String sessionId = request.getSessionId();
                    final boolean isChat = "AI_CHAT".equalsIgnoreCase(request.getRequestType());
                    final int historyLimit = 20;

                    Mono<List<AIRequest.Message>> messagesMono;
                    if (isChat && sessionId != null && !sessionId.isBlank()) {
                        messagesMono = messageRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, historyLimit)
                                .collectList()
                                .map(list -> {
                                    // 按时间正序
                                    list.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));
                                    List<AIRequest.Message> messages = new ArrayList<>();
                                    for (AIChatMessage m : list) {
                                        AIRequest.Message mm = new AIRequest.Message();
                                        mm.setRole(m.getRole());
                                        mm.setContent(m.getContent());
                                        messages.add(mm);
                                    }
                                    if (userPrompt != null && !userPrompt.isEmpty()) {
                                        boolean duplicateLast = false;
                                        if (!messages.isEmpty()) {
                                            AIRequest.Message last = messages.get(messages.size() - 1);
                                            String lastRole = last.getRole() != null ? last.getRole() : "";
                                            String lastContent = last.getContent() != null ? last.getContent() : "";
                                            duplicateLast = "user".equalsIgnoreCase(lastRole) && userPrompt.equals(lastContent);
                                        }
                                        if (!duplicateLast) {
                                            AIRequest.Message userMessage = new AIRequest.Message();
                                            userMessage.setRole("user");
                                            userMessage.setContent(userPrompt);
                                            messages.add(userMessage);
                                        }
                                    }
                                    return messages;
                                });
                    } else {
                        messagesMono = Mono.fromSupplier(() -> {
                            List<AIRequest.Message> messages = new ArrayList<>();
                            
                            // 🔥 检查是否为完整消息列表（第一条为system消息）
                            boolean isCompleteMessageList = false;
                            if (request.getHistoricalMessages() != null && !request.getHistoricalMessages().isEmpty()) {
                                UniversalAIRequestDto.MessageDto firstMsg = request.getHistoricalMessages().get(0);
                                if ("system".equalsIgnoreCase(firstMsg.getRole())) {
                                    isCompleteMessageList = true;
                                    log.info("🔥 检测到完整消息列表（第一条为system），直接使用，不再添加提示词");
                                }
                            }
                            
                            if (isCompleteMessageList) {
                                // 完整消息列表模式：直接使用 historicalMessages，不再添加
                                log.info("✅ 使用完整消息列表，数量: {}", request.getHistoricalMessages().size());
                                for (UniversalAIRequestDto.MessageDto dto : request.getHistoricalMessages()) {
                                    AIRequest.Message msg = new AIRequest.Message();
                                    msg.setRole(dto.getRole());
                                    msg.setContent(dto.getContent());
                                    messages.add(msg);
                                    log.info("  ✅ 消息{}: role={}, contentLength={}", 
                                             messages.size(), dto.getRole(), 
                                             dto.getContent() != null ? dto.getContent().length() : 0);
                                }
                            } else {
                                // 传统模式：先添加 historicalMessages（如果有），再添加当前用户消息
                                if (request.getHistoricalMessages() != null && !request.getHistoricalMessages().isEmpty()) {
                                    log.info("✅ 检测到历史消息（传统模式），数量: {}", request.getHistoricalMessages().size());
                                    for (UniversalAIRequestDto.MessageDto dto : request.getHistoricalMessages()) {
                                        AIRequest.Message msg = new AIRequest.Message();
                                        msg.setRole(dto.getRole());
                                        msg.setContent(dto.getContent());
                                        messages.add(msg);
                                        log.info("  ✅ 添加历史消息: role={}, contentLength={}", dto.getRole(), 
                                                 dto.getContent() != null ? dto.getContent().length() : 0);
                                    }
                                }
                                
                                // 添加当前用户消息（如果存在且不是历史消息的最后一条）
                                if (userPrompt != null && !userPrompt.isEmpty()) {
                                    boolean duplicateLast = false;
                                    if (!messages.isEmpty()) {
                                        AIRequest.Message last = messages.get(messages.size() - 1);
                                        String lastRole = last.getRole() != null ? last.getRole() : "";
                                        String lastContent = last.getContent() != null ? last.getContent() : "";
                                        duplicateLast = "user".equalsIgnoreCase(lastRole) && userPrompt.equals(lastContent);
                                    }
                                    if (!duplicateLast) {
                                        AIRequest.Message userMessage = new AIRequest.Message();
                                        userMessage.setRole("user");
                                        userMessage.setContent(userPrompt);
                                        messages.add(userMessage);
                                        log.info("  ✅ 添加当前用户消息: contentLength={}", userPrompt.length());
                                    }
                                }
                            }
                            
                            log.info("✅ 最终消息列表数量: {}", messages.size());
                            return messages;
                        });
                    }

                    final String finalModelName = modelName;
                    final String finalModelProvider = modelProvider;
                    final String finalModelConfigId = modelConfigId;
                    return messagesMono.map(messages -> {
                        aiRequest.setMessages(messages);

                        // 设置元数据
                        Map<String, Object> metadata = new HashMap<>();
                        metadata.put("requestType", request.getRequestType());
                        metadata.put("enableRag", true); // 启用RAG检索

                        // 传递模型配置信息到元数据
                        if (finalModelName != null) {
                            metadata.put("requestedModelName", finalModelName);
                        }
                        if (finalModelProvider != null) {
                            metadata.put("requestedModelProvider", finalModelProvider);
                        }
                        if (finalModelConfigId != null) {
                            metadata.put("requestedModelConfigId", finalModelConfigId);
                        }

                        // 👉 新增: 将参数中的 enableSmartContext 同步到 metadata，便于下游逻辑统一读取
                        if (request.getParameters() != null && request.getParameters().containsKey("enableSmartContext")) {
                            Object enableSmartContextFlag = request.getParameters().get("enableSmartContext");
                            metadata.put("enableSmartContext", enableSmartContextFlag);
                        }

                        if (request.getSessionId() != null) {
                            metadata.put("sessionId", request.getSessionId());
                        }
                        if (request.getMetadata() != null) {
                            metadata.putAll(request.getMetadata());
                        }
                        aiRequest.setMetadata(metadata);
                        // 确保 traceId 与 幂等键存在并双向同步：
                        // 1) 若两者都缺失，则生成新的 traceId 并同时写入 metadata 与 parameters.providerSpecific
                        try {
                            String existingTraceId = aiRequest.getTraceId();
                            Object existingIdem = metadata.get(BillingKeys.REQUEST_IDEMPOTENCY_KEY);
                            boolean noTraceId = (existingTraceId == null || existingTraceId.isBlank());
                            boolean noIdem = (existingIdem == null || String.valueOf(existingIdem).isBlank());
                            if (noTraceId && noIdem) {
                                String newId = java.util.UUID.randomUUID().toString();
                                aiRequest.setTraceId(newId);
                                metadata.put(BillingKeys.REQUEST_IDEMPOTENCY_KEY, newId);

                                // 同步到 parameters.providerSpecific
                                Map<String, Object> params = aiRequest.getParameters();
                                if (params == null) {
                                    params = new HashMap<>();
                                    aiRequest.setParameters(params);
                                }
                                Object psObj = params.get("providerSpecific");
                                Map<String, Object> providerSpecific;
                                if (psObj instanceof Map<?, ?>) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> casted = (Map<String, Object>) psObj;
                                    providerSpecific = new HashMap<String, Object>(casted);
                                } else {
                                    providerSpecific = new HashMap<String, Object>();
                                }
                                providerSpecific.put(BillingKeys.REQUEST_IDEMPOTENCY_KEY, newId);
                                params.put("providerSpecific", providerSpecific);
                            }
                        } catch (Exception ignore) {}
                        // 若存在幂等键则同步到 traceId 字段
                        try {
                            Object idem = metadata.get(com.ainovel.server.service.billing.BillingKeys.REQUEST_IDEMPOTENCY_KEY);
                            if (idem instanceof String s && !s.isBlank()) {
                                aiRequest.setTraceId(s);
                            }
                        } catch (Exception ignore) {}

                        // 🚀 调整debug日志，避免暴露完整的提示词内容
                        log.debug("构建的AI请求: userId={}, model={}, messages数量={}, metadata keys={}",
                                aiRequest.getUserId(), aiRequest.getModel(),
                                aiRequest.getMessages().size(),
                                aiRequest.getMetadata() != null ? aiRequest.getMetadata().keySet() : "null");

                        return aiRequest;
                    });
                });
    }

    private Mono<Map<String, String>> buildPrompts(UniversalAIRequestDto request) {
        // --- 优化开始：仅构建一次参数 Map 并复用 ---
        Mono<String> contextDataMono = getContextData(request).cache();
        Mono<Map<String, Object>> paramMono = buildPromptParameters(request, contextDataMono).cache();

        AIFeatureType featureType = mapRequestTypeToFeatureType(request.getRequestType());
        AIFeaturePromptProvider provider = promptProviderFactory.getProvider(featureType);

        Mono<String> systemPromptMono;
        Mono<String> userPromptMono;

        if (provider == null) {
            log.error("未找到功能类型 {} 的提示词提供器", featureType);
            systemPromptMono = Mono.just("你是一位专业的AI助手，请根据用户的要求提供帮助。");
            userPromptMono   = Mono.just("请根据以下内容进行处理：\n{{input}}");
        } else {
            systemPromptMono = paramMono.flatMap(params -> {
                log.debug("开始生成系统提示词(共享参数)，参数数量: {}", params.size());
                // 覆盖：若有customSystemPrompt则直接使用
                Object cs = params.get("customSystemPrompt");
                if (cs instanceof String && !((String) cs).isEmpty()) {
                    return Mono.just((String) cs);
                }
                return provider.getSystemPrompt(request.getUserId(), params)
                        .doOnSuccess(sp -> log.debug("系统提示词生成完成，长度: {} 字符", sp != null ? sp.length() : 0));
            });

            Mono<String> templateIdMono = extractPromptTemplateId(request).cache();
            userPromptMono = templateIdMono.flatMap(tid -> paramMono.flatMap(params -> {
                log.debug("开始生成用户提示词(共享参数)，templateId: {}, 参数数量: {}", tid, params.size());
                // 覆盖：若有customUserPrompt则直接使用
                Object cu = params.get("customUserPrompt");
                if (cu instanceof String && !((String) cu).isEmpty()) {
                    return Mono.just((String) cu);
                }
                return provider.getUserPrompt(request.getUserId(), tid, params)
                        .doOnSuccess(up -> log.debug("用户提示词生成完成，长度: {} 字符", up != null ? up.length() : 0));
            }));
        }

        return Mono.zip(systemPromptMono, userPromptMono)
                .map(tuple -> {
                    String systemPrompt = tuple.getT1();
                    String userPrompt   = tuple.getT2();

                    Map<String, String> prompts = new HashMap<>();
                    prompts.put("system", systemPrompt);
                    prompts.put("user", userPrompt);

                    return prompts;
                });
    }

    /**
     * 🚀 重构：使用PromptProviderFactory获取系统提示词
     */
    private Mono<String> getSystemPrompt(UniversalAIRequestDto request, Mono<String> contextDataMono) {
        AIFeatureType featureType = mapRequestTypeToFeatureType(request.getRequestType());
        log.info("获取系统提示词 - requestType: {}, featureType: {}", request.getRequestType(), featureType);
        
        // 获取对应的提示词提供器
        AIFeaturePromptProvider provider = promptProviderFactory.getProvider(featureType);
        if (provider == null) {
            log.error("未找到功能类型 {} 的提示词提供器", featureType);
            return Mono.just("你是一位专业的AI助手，请根据用户的要求提供帮助。");
        }
        
        // 构建参数Map
        return buildPromptParameters(request, contextDataMono)
                .flatMap(parameters -> {
                    log.debug("开始生成系统提示词，参数数量: {}", parameters.size());
                    
                    // 使用提示词提供器获取系统提示词
                    return provider.getSystemPrompt(request.getUserId(), parameters)
                            .doOnSuccess(systemPrompt -> log.debug("系统提示词生成完成，长度: {} 字符", 
                                                                  systemPrompt != null ? systemPrompt.length() : 0))
                            .doOnError(error -> log.error("系统提示词生成失败: {}", error.getMessage(), error));
                });
    }


    /**
     * 🚀 重构：使用PromptProviderFactory获取用户提示词
     */
    private Mono<String> getUserPrompt(UniversalAIRequestDto request, Mono<String> contextDataMono) {
        AIFeatureType featureType = mapRequestTypeToFeatureType(request.getRequestType());
        log.info("获取用户提示词 - requestType: {}, featureType: {}", request.getRequestType(), featureType);
        
        // 获取对应的提示词提供器
        AIFeaturePromptProvider provider = promptProviderFactory.getProvider(featureType);
        if (provider == null) {
            log.error("未找到功能类型 {} 的提示词提供器", featureType);
            return Mono.just("请根据以下内容进行处理：\n{{input}}");
        }
        
        // 🚀 实现提示词模板ID的优先级逻辑
        return extractPromptTemplateId(request)
                .flatMap(templateId -> {
                    log.info("🎯 提取到的提示词模板ID: {}", templateId);
                    
                    // 构建参数Map
                    return buildPromptParameters(request, contextDataMono)
                            .flatMap(parameters -> {
                                log.debug("开始生成用户提示词，templateId: {}, 参数数量: {}", templateId, parameters.size());
                                
                                // 使用提示词提供器获取用户提示词
                                return provider.getUserPrompt(request.getUserId(), templateId, parameters)
                                        .map(userPrompt -> userPrompt + buildFormatSuffix(featureType, parameters))
                                        .doOnSuccess(userPrompt -> log.debug("用户提示词生成完成（含格式说明），长度: {} 字符", 
                                                                            userPrompt != null ? userPrompt.length() : 0))
                                        .doOnError(error -> log.error("用户提示词生成失败: {}", error.getMessage(), error));
                            });
                });
    }

    /**
     * 在业务层统一附加"生成格式说明"，而不是依赖模板本身。
     * 追加到用户提示词末尾，便于模型严格遵循输出格式。
     */
    private String buildFormatSuffix(AIFeatureType featureType, Map<String, Object> parameters) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("\n\n");
            String mode = safeString(parameters.get("mode"));
            if (featureType == AIFeatureType.NOVEL_COMPOSE) {
                if ("outline".equalsIgnoreCase(mode)) {
                    // 改为强制JSON输出，避免自定义标签解析不稳
                    sb.append("[格式要求]\n")
                      .append("仅输出JSON，不要包含任何额外文本。\n")
                      .append("JSON结构如下：\n")
                      .append("{\n")
                      .append("  \"outlines\": [\n")
                      .append("    { \"index\": 1, \"title\": \"...\", \"summary\": \"...\" },\n")
                      .append("    { \"index\": 2, \"title\": \"...\", \"summary\": \"...\" }\n")
                      .append("  ]\n")
                      .append("}\n");
                } else if ("chapters".equalsIgnoreCase(mode)) {
                    sb.append("[格式要求]\n")
                      .append("仅输出JSON，不要包含任何额外文本。结构：\n")
                      .append("{ \"chapters\": [ { \"index\": 1, \"outline\": \"...\", \"content\": \"...\" } ] }\n");
                } else if ("outline_plus_chapters".equalsIgnoreCase(mode)) {
                    // 首次请求会被克隆成 outline → 统一使用 JSON 大纲，后续章节正文继续常规流式文本
                    sb.append("[格式要求]\n")
                      .append("仅输出JSON，不要包含任何额外文本。\n")
                      .append("{ \"outlines\": [ { \"index\": 1, \"title\": \"...\", \"summary\": \"...\" } ] }\n");
                }
            } else if (featureType == AIFeatureType.SUMMARY_TO_SCENE) {
                sb.append("[格式要求]\n")
                  .append("只输出完整的场景正文本身，不得输出标题、标记、解释或任何附加说明。\n");
            } else {
                // 其他功能默认不追加
                return "";
            }
            return sb.toString();
        } catch (Exception ignore) {
            return "";
        }
    }

    private String safeString(Object o) {
        return o instanceof String ? (String) o : "";
    }

    /**
     * 🚀 新增：提取提示词模板ID，实现优先级逻辑
     * 优先级：1. 请求参数中的promptTemplateId > 2. 用户默认模板 > 3. 系统默认模板(null)
     */
    private Mono<String> extractPromptTemplateId(UniversalAIRequestDto request) {
        AIFeatureType featureType = mapRequestTypeToFeatureType(request.getRequestType());

        // 1. 🚀 优先级1：检查请求参数中是否指定了promptTemplateId
        String explicitTemplateId = extractExplicitTemplateId(request);
        if (explicitTemplateId != null && !explicitTemplateId.isEmpty()) {
            log.info("🎯 使用明确指定的提示词模板ID: {}", explicitTemplateId);
            return validateAndReturnTemplateId(explicitTemplateId, request.getUserId());
        }

        // 2. 🚀 优先级2：查找用户该功能类型的默认模板
        log.info("🔍 未指定模板ID，查找用户默认模板 - userId: {}, featureType: {}", request.getUserId(), featureType);
        return enhancedUserPromptService.getDefaultTemplate(request.getUserId(), featureType)
                .map(defaultTemplate -> {
                    log.info("✅ 找到用户默认模板: {}", defaultTemplate.getId());
                    return defaultTemplate.getId();
                })
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    log.info("⚠️ 未找到用户默认模板，获取系统默认模板ID");
                    AIFeaturePromptProvider provider = promptProviderFactory.getProvider(featureType);
                    if (provider != null) {
                        String systemTemplateId = provider.getSystemTemplateId();
                        if (systemTemplateId != null && !systemTemplateId.isEmpty()) {
                            log.info("✅ 找到系统默认模板ID: {}", systemTemplateId);
                            return systemTemplateId;
                        }
                    }
                    log.info("⚠️ 系统默认模板ID为空，使用Provider内置默认");
                    return "";
                }))
                .onErrorResume(error -> {
                    log.warn("查找用户默认模板时出错: {}, 回退到系统默认", error.getMessage());
                    return Mono.just("");
                });
    }

    /**
     * 🚀 新增：从请求中提取明确指定的模板ID
     */
    private String extractExplicitTemplateId(UniversalAIRequestDto request) {
        // 1. 从parameters中获取
        if (request.getParameters() != null) {
            Object templateIdParam = request.getParameters().get("promptTemplateId");
            if (templateIdParam instanceof String && !((String) templateIdParam).isEmpty()) {
                return (String) templateIdParam;
            }
            
            // 兼容其他可能的参数名
            Object associatedTemplateId = request.getParameters().get("associatedTemplateId");
            if (associatedTemplateId instanceof String && !((String) associatedTemplateId).isEmpty()) {
                return (String) associatedTemplateId;
            }
        }
        
        // 2. 从metadata中获取
        if (request.getMetadata() != null) {
            Object templateIdMeta = request.getMetadata().get("promptTemplateId");
            if (templateIdMeta instanceof String && !((String) templateIdMeta).isEmpty()) {
                return (String) templateIdMeta;
            }
            
            Object associatedTemplateIdMeta = request.getMetadata().get("associatedTemplateId");
            if (associatedTemplateIdMeta instanceof String && !((String) associatedTemplateIdMeta).isEmpty()) {
                return (String) associatedTemplateIdMeta;
            }
        }
        
        return null;
    }

    /**
     * 🚀 新增：验证模板ID的有效性
     */
    private Mono<String> validateAndReturnTemplateId(String templateId, String userId) {
        if (templateId == null || templateId.isEmpty()) {
            return Mono.just("");
        }
        
        // 🚀 处理系统默认模板ID（格式：system_default_XXX）
        if (templateId.startsWith("system_default_")) {
            log.info("🔧 检测到系统默认模板ID: {}", templateId);
            return Mono.just(templateId); // 直接返回，由Provider处理
        }
        
        // 🚀 处理公共模板ID（格式：public_XXX）
        if (templateId.startsWith("public_")) {
            log.info("🔧 检测到公共模板ID: {}", templateId);
            String actualId = templateId.substring("public_".length());
            return Mono.just(actualId); // 返回实际的公共模板ID
        }
        
        // 🚀 处理用户自定义模板ID - 验证权限
        return enhancedUserPromptService.getPromptTemplateById(userId, templateId)
                .map(template -> {
                    log.info("✅ 验证模板权限成功: templateId={}, userId={}", templateId, userId);
                    return templateId;
                })
                .onErrorResume(error -> {
                    log.warn("模板ID验证失败: templateId={}, userId={}, error={}", templateId, userId, error.getMessage());
                    // 验证失败时返回空字符串，回退到默认逻辑
                    return Mono.just("");
                });
    }

    /**
     * 🚀 新增：构建提示词参数Map
     */
    private Mono<Map<String, Object>> buildPromptParameters(UniversalAIRequestDto request, Mono<String> contextDataMono) {
        log.info("🔧 构建提示词参数 - requestType: {}, userId: {}, novelId: {}", 
                 request.getRequestType(), request.getUserId(), request.getNovelId());
        
        // 记录前端传递的关键参数
        log.info("📨 前端传参详情:");
        log.info("   prompt: {}", request.getPrompt() != null ? 
                 (request.getPrompt().length() > 100 ? request.getPrompt().substring(0, 100) + "..." : request.getPrompt()) : "null");
        log.info("   selectedText: {}", request.getSelectedText() != null ? "有内容(" + request.getSelectedText().length() + "字符)" : "null");
        log.info("   instructions: {}", request.getInstructions());
        log.info("   parameters: {}", request.getParameters());
        log.info("   metadata: {}", request.getMetadata());
        
        // === 改为非阻塞：并行获取context与novel ===
        Mono<Novel> novelMono = request.getNovelId() != null ?
                novelService.findNovelById(request.getNovelId())
                        .onErrorResume(e -> {
                            log.warn("获取小说基本信息失败: {}", e.getMessage());
                            return Mono.empty();
                        })
                        .defaultIfEmpty(new Novel()) // ⚠️ 修复：Mono.just(null) 会导致 NPE，改为返回一个空 Novel 实例，避免阻塞 zip
                : Mono.just(new Novel());

        return Mono.zip(contextDataMono, novelMono)
            .map(tuple -> {
            String contextData = tuple.getT1();
            Novel novel = tuple.getT2();
            Map<String, Object> parameters = new HashMap<>();
            // 基础参数
            if (request.getUserId() != null) {
                parameters.put("userId", request.getUserId());
            }
            if (request.getNovelId() != null) {
                parameters.put("novelId", request.getNovelId());
            }
            if (request.getSessionId() != null) {
                parameters.put("sessionId", request.getSessionId());
            }
            
            // 输入内容相关参数
            String inputContent = "";
            if (request.getSelectedText() != null && !request.getSelectedText().isEmpty()) {
                inputContent = request.getSelectedText();
                log.debug("   使用selectedText作为input: {} 字符", inputContent.length());
            } else if (request.getPrompt() != null && !request.getPrompt().isEmpty()) {
                inputContent = request.getPrompt();
                log.debug("   使用prompt作为input: {} 字符", inputContent.length());
            }
            parameters.put("input", inputContent);
            
            // 消息内容（聊天专用）
            if ("chat".equals(request.getRequestType()) && request.getPrompt() != null) {
                parameters.put("message", request.getPrompt());
                log.debug("   添加message参数（聊天专用）: {} 字符", request.getPrompt().length());
            }
            
            // 上下文信息
            parameters.put("context", contextData != null ? contextData : "");
            log.debug("   添加context参数: {} 字符", contextData != null ? contextData.length() : 0);
            
            // 指令信息
            if (request.getInstructions() != null) {
                parameters.put("instructions", request.getInstructions());
                log.debug("   添加instructions参数: {}", request.getInstructions());
            }

            // 新增：传递当前章节/场景ID，供Provider与占位符解析使用
            if (request.getChapterId() != null && !request.getChapterId().isEmpty()) {
                parameters.put("chapterId", request.getChapterId());
                parameters.put("currentChapterId", request.getChapterId());
            }
            if (request.getSceneId() != null && !request.getSceneId().isEmpty()) {
                parameters.put("sceneId", request.getSceneId());
                parameters.put("currentSceneId", request.getSceneId());
            }
            
            // 从请求参数中复制所有参数
            if (request.getParameters() != null) {
                request.getParameters().forEach((key, value) -> {
                    parameters.put(key, value);
                    log.debug("   复制参数: {} = {}", key, value);
                });
            }

            // 兼容：将临时自定义提示词提升为独立键，供上游覆盖逻辑读取
            if (request.getParameters() != null) {
                Object customSystem = request.getParameters().get("customSystemPrompt");
                Object customUser = request.getParameters().get("customUserPrompt");
                if (customSystem instanceof String && !((String) customSystem).isEmpty()) {
                    parameters.put("customSystemPrompt", customSystem);
                    log.debug("   检测到自定义系统提示词(customSystemPrompt)覆盖");
                }
                if (customUser instanceof String && !((String) customUser).isEmpty()) {
                    parameters.put("customUserPrompt", customUser);
                    log.debug("   检测到自定义用户提示词(customUserPrompt)覆盖");
                }
            }
            
            // 智能上下文开关
            if (request.getMetadata() != null) {
                Boolean enableSmartContext = (Boolean) request.getMetadata().get("enableSmartContext");
                if (enableSmartContext != null) {
                    parameters.put("enableSmartContext", enableSmartContext);
                    log.debug("   添加enableSmartContext: {}", enableSmartContext);
                }
            }
            
            // 🚀 修复：小说基本信息 - 非阻塞版本
            if (novel != null) {
                parameters.put("novelTitle", novel.getTitle() != null ? novel.getTitle() : "未命名小说");
                parameters.put("authorName", novel.getAuthor() != null ? novel.getAuthor() : "未知作者");
            } else if (request.getNovelId() != null) {
                // 查询失败或不存在
                parameters.put("novelTitle", "未知小说");
                parameters.put("authorName", "未知作者");
            } else {
                // 如果没有novelId，使用默认值
                parameters.put("novelTitle", "当前写作");
                parameters.put("authorName", "作者");
            }
            
            // 记录用户勾选的上下文类型集合，供占位符解析器过滤使用
            if (request.getContextSelections() != null && !request.getContextSelections().isEmpty()) {
                Set<String> selectedProviderTypes = request.getContextSelections().stream()
                        .map(sel -> sel.getType() == null ? "" : sel.getType().toLowerCase())
                        .collect(Collectors.toSet());
                parameters.put("selectedProviderTypes", selectedProviderTypes);
                
                // 🚀 新增：传递完整的上下文选择数据给ContextualPlaceholderResolver
                parameters.put("contextSelections", request.getContextSelections());
            }
            
            log.info("✅ 提示词参数构建完成，总参数数量: {}, 参数列表: {}", parameters.size(), parameters.keySet());
            return parameters;
        });
    }
    




    /**
     * 获取上下文数据 - 重构版本使用ContentProvider系统
     */
    private Mono<String> getContextData(UniversalAIRequestDto request) {
        List<Mono<String>> contextSources = new ArrayList<>();

        // 🚀 优先使用前端传来的contextSelections（通过ContentProvider系统）
        if (request.getContextSelections() != null && !request.getContextSelections().isEmpty()) {
            log.info("处理前端上下文选择，数量: {}", request.getContextSelections().size());
            contextSources.add(getSelectedContextData(request));
            
            // 当有明确的上下文选择时，只保留小说基本信息和RAG检索
            // 其他上下文（场景、章节、设定）都通过ContentProvider获取，避免重复
            if (request.getNovelId() != null) {
                contextSources.add(getNovelBasicInfo(request.getNovelId()));
            }
            
            // 获取RAG检索结果
            if (request.getNovelId() != null && request.getMetadata() != null && request.getMetadata().get("enableSmartContext") != null) {
                //TODO rag暂时不介入
                //contextSources.add(getSmartRetrievalContent(request));
            }
        } else {
            // 🚀 向后兼容：当没有contextSelections时，使用传统方式但通过ContentProvider获取
            log.info("没有上下文选择，使用传统上下文获取方式");
            
            // 获取小说基本信息
            if (request.getNovelId() != null) {
                contextSources.add(getNovelBasicInfo(request.getNovelId()));
            }

            // 🚀 使用ContentProvider获取场景上下文
            if (request.getSceneId() != null) {
                contextSources.add(getContextFromProvider("scene", "scene_" + request.getSceneId(), request));
            }

            // 🚀 使用ContentProvider获取章节上下文（传入纯UUID，不再拼接前缀）
            if (request.getChapterId() != null) {
                contextSources.add(getContextFromProvider("chapter", request.getChapterId(), request));
            }

            // // 🚀 暂时保留相关设定的原有实现，因为这个需要智能检索
            // // TODO: 将来可以考虑创建一个智能设定Provider来替代这个实现
            // if (request.getNovelId() != null && isNonChatRequest(request)) {
            //     contextSources.add(getIntelligentSettingsContent(request));
            // }

            // 获取RAG检索结果
            if (request.getNovelId() != null && request.getMetadata() != null && request.getMetadata().get("enableSmartContext") != null) {
                //TODO rag暂时不接入
                //contextSources.add(getSmartRetrievalContent(request));
            }
        }

        // 合并所有上下文
        return Flux.merge(contextSources)
                .filter(context -> context != null && !context.isEmpty())
                .collect(Collectors.joining("\n\n"))
                .defaultIfEmpty("");
    }

    /**
     * 🚀 新增：通过ContentProvider获取上下文数据的统一方法
     */
    private Mono<String> getContextFromProvider(String type, String id, UniversalAIRequestDto request) {
        Optional<ContentProvider> providerOptional = contentProviderFactory.getProvider(type);
        if (providerOptional.isPresent()) {
            ContentProvider provider = providerOptional.get();
            return provider.getContent(id, request)
                    .map(ContentResult::getContent)
                    .filter(content -> content != null && !content.trim().isEmpty())
                    .doOnNext(content -> log.debug("通过Provider获取{}上下文成功: id={}, length={}", 
                                                  type, id, content.length()))
                    .onErrorResume(error -> {
                        log.error("通过Provider获取{}上下文失败: id={}, error={}", type, id, error.getMessage());
                        return Mono.just("");
                    });
        } else {
            log.warn("未找到类型为 {} 的ContentProvider", type);
            return Mono.just("");
        }
    }

    /**
     * 🚀 新增：处理前端选择的上下文数据（使用预处理去重逻辑）
     */
    private Mono<String> getSelectedContextData(UniversalAIRequestDto request) {

        // 🚀 第一步：日志并快速返回
        if (request.getContextSelections() == null || request.getContextSelections().isEmpty()) {
            log.info("没有选择任何上下文数据");
            return Mono.just("");
        }

        log.info("原始上下文选择数量: {}, 详情: {}", 
                 request.getContextSelections().size(),
                 request.getContextSelections().stream()
                         .map(s -> s.getType() + ":" + s.getId())
                         .collect(Collectors.joining(", ")));

        // �� 使用异步缓存索引去重
        return preprocessAndDeduplicateSelectionsAsync(request.getContextSelections(), request.getNovelId())
                .flatMap(optimizedSelections -> {
                    if (optimizedSelections.isEmpty()) {
                        log.info("预处理后没有有效的上下文选择");
                        return Mono.just("");
                    }

                    log.info("预处理后的上下文选择数量: {}, 详情: {}", 
                             optimizedSelections.size(),
                             optimizedSelections.stream()
                                     .map(s -> s.getType() + ":" + s.getId())
                                     .collect(Collectors.joining(", ")));

                    // 🚀 第三步：根据优化后的选择列表获取内容
                    List<Mono<String>> contentMappings = new ArrayList<>();

                    for (UniversalAIRequestDto.ContextSelectionDto contextSelection : optimizedSelections) {
                        String rawId = contextSelection.getId();
                        // 兼容前端扁平化ID，例如 flat_chapter_xxx → chapter_xxx
                        final String resolvedId = (rawId != null && rawId.startsWith("flat_"))
                                ? rawId.substring("flat_".length())
                                : rawId;

                        final String type = contextSelection.getType();

                        log.info("获取上下文内容: id={}, type={}, 可用提供器: {}", 
                                 resolvedId, type, contentProviderFactory.getAvailableTypes());

                        if (type != null) {
                            Optional<ContentProvider> providerOptional = contentProviderFactory.getProvider(type.toLowerCase());
                            if (providerOptional.isPresent()) {
                                ContentProvider provider = providerOptional.get();
                                Mono<String> contentMono = provider.getContent(resolvedId, request)
                                        .map(ContentResult::getContent)
                                        .filter(content -> content != null && !content.trim().isEmpty())
                                        .doOnNext(content -> log.info("成功获取内容: type={}, id={}, length={}", 
                                                                     type, resolvedId, content.length()))
                                        .onErrorResume(error -> {
                                            log.error("获取{}内容失败: id={}, error={}", type, resolvedId, error.getMessage(), error);
                                            return Mono.just("");
                                        });
                                contentMappings.add(contentMono);
                            } else {
                                log.warn("未找到类型为 {} 的内容提供器，可用提供器: {}", type, contentProviderFactory.getAvailableTypes());
                            }
                        }
                    }

                    if (contentMappings.isEmpty()) {
                        log.warn("没有有效的内容提供器，返回空内容");
                        return Mono.just("");
                    }

                    return Flux.merge(contentMappings)
                            .filter(content -> !content.isEmpty())
                            .collect(Collectors.joining("\n\n"))
                            .map(combinedContent -> {
                                if (combinedContent.isEmpty()) {
                                    log.warn("所有内容获取后为空");
                                    return "";
                                }
                                log.info("合并上下文完成，最终内容长度: {} 字符", combinedContent.length());
                                return combinedContent;
                            });
                });
    }


    /**
     * 🚀 获取内容类型的优先级
     */
    private int getTypePriority(String type) {
        if (type == null) return Integer.MAX_VALUE;
        
        switch (type.toLowerCase()) {
            case TYPE_FULL_NOVEL_TEXT:
                return PRIORITY_FULL_NOVEL_TEXT;
            case TYPE_FULL_NOVEL_SUMMARY:
                return PRIORITY_FULL_NOVEL_SUMMARY;
            case TYPE_ACT:
                return PRIORITY_ACT;
            case TYPE_CHAPTER:
                return PRIORITY_CHAPTER;
            case TYPE_SCENE:
                return PRIORITY_SCENE;
            case TYPE_CHARACTER:
            case TYPE_LOCATION:
            case TYPE_ITEM:
            case TYPE_LORE:
                return PRIORITY_SETTING;
            case TYPE_SNIPPET:
                return PRIORITY_SNIPPET;
            default:
                return Integer.MAX_VALUE;
        }
    }

    /**
     * 🚀 标准化ID格式
     */
    private String normalizeId(String type, String id) {
        if (type == null || id == null) return "";
        
        // 处理格式如：chapter_xxx, scene_xxx, setting_xxx, snippet_xxx
        if (id.contains("_")) {
            return id; // 已经是标准格式
        }
        
        // 为不同类型添加前缀
        switch (type.toLowerCase()) {
            case TYPE_SCENE:
                return "scene_" + id;
            case TYPE_CHAPTER:
                return "chapter_" + id;
            case TYPE_CHARACTER:
            case TYPE_LOCATION:
            case TYPE_ITEM:
            case TYPE_LORE:
                return "setting_" + id;
            case TYPE_SNIPPET:
                return "snippet_" + id;
            default:
                return type.toLowerCase() + "_" + id;
        }
    }


    // 🚀 移除：这些方法已移动到对应的独立Provider类中
    // - getFullNovelTextContent -> FullNovelTextProvider
    // - getFullNovelSummaryContent -> FullNovelSummaryProvider  
    // - getActContent -> ActProvider
    // - getChapterContentWithScenes -> ChapterProvider
    // - getChapterSequenceNumber -> ChapterProvider

    /**
     * 调用AI服务
     */
    private Mono<AIResponse> callAIService(AIRequest aiRequest, String requestType) {
        switch (requestType.toLowerCase()) {
            case "chat":
                return novelAIService.generateChatResponse(
                        aiRequest.getUserId(), 
                        getSessionId(aiRequest), 
                        getUserMessage(aiRequest), 
                        aiRequest.getMetadata()
                );
            case "expansion":
            case "summary":
            case "refactor":
            case "generation":
            default:
                // 检查是否指定了特定的模型配置
                final String requestedModelName;
                final String requestedModelConfigId;
                
                if (aiRequest.getMetadata() != null) {
                    requestedModelName = (String) aiRequest.getMetadata().get("requestedModelName");
                    requestedModelConfigId = (String) aiRequest.getMetadata().get("requestedModelConfigId");
                } else {
                    requestedModelName = null;
                    requestedModelConfigId = null;
                }
                
                // 如果指定了模型配置ID，优先使用
                if (requestedModelConfigId != null && !requestedModelConfigId.isEmpty()) {
                    log.info("使用指定的模型配置ID: {}", requestedModelConfigId);
                    return novelAIService.getAIModelProviderByConfigId(aiRequest.getUserId(), requestedModelConfigId)
                            .flatMap(provider -> {
                                log.info("获取到指定配置的AI模型提供商: {}, 开始生成", provider.getModelName());
                                return provider.generateContent(aiRequest);
                            });
                }
                // 如果指定了模型名称，使用指定的模型
                else if (requestedModelName != null && !requestedModelName.isEmpty()) {
                    log.info("使用指定的模型名称: {}", requestedModelName);
                    return novelAIService.getAIModelProvider(aiRequest.getUserId(), requestedModelName)
                            .flatMap(provider -> {
                                log.info("获取到指定模型的AI模型提供商: {}, 开始生成", provider.getModelName());
                                return provider.generateContent(aiRequest);
                            })
                            .onErrorResume(error -> {
                                log.error("使用指定模型名称 {} 失败，回退到默认流程: {}", requestedModelName, error.getMessage());
                                // 回退到默认的生成方法
                                return novelAIService.generateNovelContent(aiRequest);
                            });
                }
                // 使用默认的生成方法
                else {
                    log.info("未指定特定模型，使用默认生成方法");
                    return novelAIService.generateNovelContent(aiRequest);
                }
        }
    }

    /**
     * 调用流式AI服务
     */
    @Trace(operationName = "ai.universal.stream")
    private Flux<String> callAIServiceStream(AIRequest aiRequest, String requestType) {
        switch (requestType.toLowerCase()) {
            case "chat":
                return novelAIService.generateChatResponseStream(
                        aiRequest.getUserId(), 
                        getSessionId(aiRequest), 
                        getUserMessage(aiRequest), 
                        aiRequest.getMetadata()
                );
            case "expansion":
            case "summary":
            case "refactor":
            case "generation":
            default:
                // 检查是否指定了特定的模型配置
                final String requestedModelName;
                final String requestedModelConfigId;
                
                if (aiRequest.getMetadata() != null) {
                    requestedModelName = (String) aiRequest.getMetadata().get("requestedModelName");
                    requestedModelConfigId = (String) aiRequest.getMetadata().get("requestedModelConfigId");
                } else {
                    requestedModelName = null;
                    requestedModelConfigId = null;
                }
                
                // 如果指定了模型配置ID，优先使用
                if (requestedModelConfigId != null && !requestedModelConfigId.isEmpty()) {
                    log.info("使用指定的模型配置ID: {}", requestedModelConfigId);
                    return novelAIService.getAIModelProviderByConfigId(aiRequest.getUserId(), requestedModelConfigId)
                            .flatMapMany(provider -> {
                                log.info("获取到指定配置的AI模型提供商: {}, 开始流式生成", provider.getModelName());
                                return provider.generateContentStream(aiRequest)
                                        .filter(chunk -> chunk != null && !chunk.isBlank() && !"heartbeat".equalsIgnoreCase(chunk));
                            });
                }
                // 使用默认的流式生成方法
                else {
                    log.error("未指定特定模型，流程错误");
                    throw new RuntimeException("未指定特定模型，流程错误");
                }
        }
    }

    /**
     * 🚀 重构：处理公共模型流式请求 - 改为后扣费模式（流式特殊处理）
     * 注意：流式请求无法在过程中获取token使用量，依赖观测系统后续处理
     */
    // 已收敛到按 configId 获取 Provider 的统一路径（公共模型逻辑下沉到装饰器层）

    /**
     * 转换为响应DTO
     */
    private UniversalAIResponseDto convertToResponseDto(AIResponse aiResponse, String requestType) {
        // 🚀 非预览接口不返回提示词内容，节约资源
        Map<String, Object> responseMetadata = new HashMap<>();
        if (aiResponse.getMetadata() != null) {
            // 只保留必要的元数据，不包含完整提示词
            Object modelName = aiResponse.getMetadata().get("modelName");
            Object promptPresetId = aiResponse.getMetadata().get("promptPresetId");
            Object streamed = aiResponse.getMetadata().get("streamed");
            
            if (modelName != null) {
                responseMetadata.put("modelName", modelName);
            }
            if (promptPresetId != null) {
                responseMetadata.put("promptPresetId", promptPresetId);
            }
            if (streamed != null) {
                responseMetadata.put("streamed", streamed);
            }
        }
        
        return UniversalAIResponseDto.builder()
                .id(UUID.randomUUID().toString())
                .requestType(requestType)
                .content(aiResponse.getContent())
                .finishReason(aiResponse.getFinishReason())
                .tokenUsage(convertTokenUsage(aiResponse.getTokenUsage()))
                .model(aiResponse.getModel())
                .createdAt(LocalDateTime.now())
                .metadata(responseMetadata)
                .build();
    }

    /**
     * 转换为流式响应DTO
     */
    /**
     * 检查流式内容是否有效，用于在 map 操作前进行过滤。
     * @param content 从模型流接收到的内容
     * @return 如果内容有效则返回 true，否则返回 false
     */
    private boolean isValidStreamContent(String content) {
        if (content == null || content.trim().isEmpty()) {
            log.debug("忽略空流式内容片段");
            return false;
        }

        // 🚀 对 "}" 或 "[DONE]" 或 "---" 之类的伪结束标记直接忽略，避免提前发送结束信号
        String trimmed = content.trim();
        if ("}".equals(trimmed) || "[DONE]".equalsIgnoreCase(trimmed) || "---".equals(trimmed)) {
            log.debug("忽略伪结束标记片段: {}", trimmed);
            return false;
        }
        
        return true;
    }

    private UniversalAIResponseDto convertToStreamResponseDto(String content, String requestType) {
        // 由于已经在 filter 中验证了内容有效性，这里可以直接处理
        // 🚀 流式响应不返回任何元数据，进一步节约资源
        return UniversalAIResponseDto.builder()
                .id(UUID.randomUUID().toString())
                .requestType(requestType)
                .content(content)
                .finishReason(null)
                .tokenUsage(null)
                .model(null)
                .createdAt(LocalDateTime.now())
                .metadata(new HashMap<>()) // 流式响应保持空的metadata
                .build();
    }

    /**
     * 转换Token使用情况
     */
    private UniversalAIResponseDto.TokenUsageDto convertTokenUsage(Object tokenUsage) {
        // 这里需要根据实际的TokenUsage类型进行转换
        if (tokenUsage == null) {
            return null;
        }
        
        return UniversalAIResponseDto.TokenUsageDto.builder()
                .promptTokens(0)
                .completionTokens(0)
                .totalTokens(0)
                .build();
    }

    /**
     * 🚀 重构：映射前端请求类型到后端AI特性类型
     * 确保与前端AIRequestType枚举的正确对应
     */
    private AIFeatureType mapRequestTypeToFeatureType(String requestType) {
        if (requestType == null) {
            log.warn("请求类型为null，默认使用AI_CHAT");
            return AIFeatureType.AI_CHAT;
        }

        final String rt = requestType.trim();
        if (rt.isEmpty()) {
            return AIFeatureType.AI_CHAT;
        }

        // 兼容大小写与历史别名
        String key = rt.toLowerCase();
        switch (key) {
            case "chat":
                return AIFeatureType.AI_CHAT;
            case "expansion":
                return AIFeatureType.TEXT_EXPANSION;
            case "summary":
                return AIFeatureType.TEXT_SUMMARY;
            case "refactor":
                return AIFeatureType.TEXT_REFACTOR;
            case "generation":
                return AIFeatureType.NOVEL_GENERATION;
            case "scene_to_summary":
                return AIFeatureType.SCENE_TO_SUMMARY;
            case "summary_to_scene":
                return AIFeatureType.SUMMARY_TO_SCENE;
            case "novel_compose":
                return AIFeatureType.NOVEL_COMPOSE;
            case "story_prediction_summary":
                // 取消 STORY_PREDICTION_*：统一映射至通用功能
                return AIFeatureType.SCENE_TO_SUMMARY;
            case "story_prediction_scene":
                return AIFeatureType.SUMMARY_TO_SCENE;
            default:
                try {
                    // 兜底：尝试按照枚举名解析（要求传入大写枚举名）
                    return AIFeatureType.valueOf(rt);
                } catch (Exception ignore) {
                    try {
                        return AIFeatureType.valueOf(rt.toUpperCase());
                    } catch (Exception e) {
                        log.warn("无法映射请求类型: {}，默认使用AI_CHAT", requestType);
                        return AIFeatureType.AI_CHAT;
                    }
                }
        }
    }

 





    /**
     * 估算Token数量
     */
    private Integer estimateTokens(String text) {
        if (text == null) return 0;
        // 简单估算：英文按4个字符一个token，中文按1.5个字符一个token
        int chineseChars = 0;
        int otherChars = 0;
        
        for (char c : text.toCharArray()) {
            if (c >= 0x4e00 && c <= 0x9fff) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        
        return (int) (chineseChars / 1.5 + otherChars / 4.0);
    }


    /**
     * 从AI请求中获取会话ID
     */
    private String getSessionId(AIRequest aiRequest) {
        if (aiRequest.getMetadata() != null && aiRequest.getMetadata().containsKey("sessionId")) {
            return (String) aiRequest.getMetadata().get("sessionId");
        }
        return null;
    }

    /**
     * 从AI请求中获取用户消息
     */
    private String getUserMessage(AIRequest aiRequest) {
        return aiRequest.getMessages().stream()
                .filter(msg -> "user".equals(msg.getRole()))
                .map(AIRequest.Message::getContent)
                .reduce((first, second) -> second) // 获取最后一条用户消息
                .orElse("");
    }

    /**
     * 🚀 保留：通用的ID提取工具方法
     */
    private String extractIdFromContextId(String contextId) {
        if (contextId == null || contextId.isEmpty()) {
            return null;
        }
        
        // 扁平化前缀 flat_*
        if (contextId.startsWith("flat_")) {
            String withoutFlat = contextId.substring("flat_".length());
            int idx = withoutFlat.indexOf("_");
            if (idx >= 0 && idx + 1 < withoutFlat.length()) {
                return withoutFlat.substring(idx + 1);
            }
            return withoutFlat;
        }

        int first = contextId.indexOf("_");
        if (first >= 0 && first + 1 < contextId.length()) {
            return contextId.substring(first + 1);
        }
        
        return contextId;
    }

    /**
     * 🚀 新增：确保章节ID为纯UUID格式（去掉前缀）
     * 用于修复数据库中chapterId字段格式变更后的兼容性问题
     */
    private String normalizeChapterIdForQuery(String chapterId) {
        if (chapterId == null || chapterId.isEmpty()) {
            return chapterId;
        }
        
        // 如果包含"chapter_"前缀，去掉它
        if (chapterId.startsWith("chapter_")) {
            return chapterId.substring("chapter_".length());
        }
        
        // 如果是扁平化格式 flat_chapter_xxx
        if (chapterId.startsWith("flat_chapter_")) {
            return chapterId.substring("flat_chapter_".length());
        }
        
        // 其他情况直接返回
        return chapterId;
    }





    /**
     * 优先从parameters.providerSpecific与metadata中提取公共模型配置ID
     */
    @SuppressWarnings("unchecked")
    private String extractPublicModelConfigId(AIRequest aiRequest) {
        try {
            if (aiRequest.getParameters() != null) {
                Object psRaw = aiRequest.getParameters().get("providerSpecific");
                if (psRaw instanceof Map<?, ?> m) {
                    Object id = ((Map<?, ?>) m).get(com.ainovel.server.service.billing.BillingKeys.PUBLIC_MODEL_CONFIG_ID);
                    if (id instanceof String s && !s.isBlank()) return s;
                }
            }
            if (aiRequest.getMetadata() != null) {
                Object id1 = aiRequest.getMetadata().get("publicModelConfigId");
                if (id1 instanceof String s1 && !s1.isBlank()) return s1;
                Object id2 = aiRequest.getMetadata().get("publicModelId");
                if (id2 instanceof String s2 && !s2.isBlank()) return s2;
            }
        } catch (Exception ignore) {}
        return null;
    }

    /**
     * 🚀 重构：处理公共模型请求，改为基于真实token使用量的后扣费模式
     */
    // 已收敛到按 configId 获取 Provider 的统一路径（公共模型逻辑下沉到装饰器层）





    /**
     * 🚀 新增：估算token数量和积分成本的辅助类
     */
    private static class TokenCostInfo {
        final int inputTokens;
        final int outputTokens;
        final long estimatedCost;
        
        TokenCostInfo(int inputTokens, int outputTokens, long estimatedCost) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.estimatedCost = estimatedCost;
        }
    }

    /**
     * 🚀 新增：估算token数量和积分成本
     */
    private Mono<TokenCostInfo> estimateTokensAndCost(AIRequest aiRequest, String provider, String modelId, AIFeatureType featureType) {
        // 简单估算输入token数量（基于提示词和消息内容）
        int calculatedInputTokens = 0;
        
        if (aiRequest.getPrompt() != null) {
            calculatedInputTokens += estimateTokens(aiRequest.getPrompt());
        }
        
        if (aiRequest.getMessages() != null) {
            for (var message : aiRequest.getMessages()) {
                if (message.getContent() != null) {
                    calculatedInputTokens += estimateTokens(message.getContent());
                }
            }
        }
        
        // 估算输出token数量
        final int inputTokens = calculatedInputTokens;
        final int outputTokens = estimateOutputTokensForFeature(inputTokens, featureType);
        
        // 计算积分成本
        return creditService.calculateCreditCost(provider, modelId, featureType, inputTokens, outputTokens)
                .map(cost -> new TokenCostInfo(inputTokens, outputTokens, cost))
                .doOnNext(costInfo -> log.debug("Token预估 - 输入: {}, 输出: {}, 积分: {}", 
                                               costInfo.inputTokens, costInfo.outputTokens, costInfo.estimatedCost));
    }

    /**
     * 🚀 新增：根据功能类型估算输出token数量
     */
    private int estimateOutputTokensForFeature(int inputTokens, AIFeatureType featureType) {
        switch (featureType) {
            case TEXT_EXPANSION:
                return (int) (inputTokens * 1.5);
            case TEXT_SUMMARY:
            case SCENE_TO_SUMMARY:
                return (int) (inputTokens * 0.3);
            case TEXT_REFACTOR:
                return (int) (inputTokens * 1.1);
            case NOVEL_GENERATION:
            case SUMMARY_TO_SCENE:
                return (int) (inputTokens * 2.0);
            case AI_CHAT:
                return (int) (inputTokens * 0.8);
            default:
                return inputTokens;
        }
    }

    /**
     * 🚀 新增：提取模型名称
     */
    private String extractModelName(UniversalAIRequestDto request) {
        // 从元数据中获取
        if (request.getMetadata() != null) {
            Object modelNameObj = request.getMetadata().get("modelName");
            if (modelNameObj instanceof String) {
                return (String) modelNameObj;
            }
        }
        
        // 从请求参数中获取（备用）
        if (request.getParameters() != null) {
            Object modelNameParam = request.getParameters().get("modelName");
            if (modelNameParam instanceof String) {
                return (String) modelNameParam;
            }
        }
        
        return null;
    }

    /**
     * 🚀 新增：提取模型提供商
     */
    private String extractModelProvider(UniversalAIRequestDto request) {
        if (request.getMetadata() != null) {
            Object modelProviderObj = request.getMetadata().get("modelProvider");
            if (modelProviderObj instanceof String) {
                return (String) modelProviderObj;
            }
        }
        return null;
    }

    /**
     * 🚀 新增：提取模型配置ID
     */
    private String extractModelConfigId(UniversalAIRequestDto request) {
        // 优先从直接字段获取
        if (request.getModelConfigId() != null) {
            return request.getModelConfigId();
        }
        
        // 从元数据中获取
        if (request.getMetadata() != null) {
            Object modelConfigIdObj = request.getMetadata().get("modelConfigId");
            if (modelConfigIdObj instanceof String) {
                return (String) modelConfigIdObj;
            }
        }
        
        return null;
    }

    /**
     * 🚀 获取小说基本元信息 - 保留原实现，因为不需要通过ContentProvider
     * 这个方法获取的是小说的基本元信息（标题、简介、类型等），不是内容数据
     */
    private Mono<String> getNovelBasicInfo(String novelId) {
        return novelService.findNovelById(novelId)
                .map(novel -> {
                    StringBuilder context = new StringBuilder();
                    context.append("=== 小说信息 ===\n");
                    context.append("标题: ").append(novel.getTitle()).append("\n");
                    if (novel.getDescription() != null) {
                        context.append("简介: ").append(novel.getDescription()).append("\n");
                    }
                    if (novel.getGenre() != null) {
                        context.append("类型: ").append(novel.getGenre()).append("\n");
                    }
                    return context.toString();
                })
                .onErrorReturn("");
    }

    // 🚀 移除：这些方法已被ContentProvider系统替代
    // - getSceneContext -> SceneProvider
    // - getChapterContext -> ChapterProvider
    // 现在通过getContextFromProvider统一获取

    /**
     * 🚀 新增：获取智能匹配的设定内容
     */
    private Mono<String> getIntelligentSettingsContent(UniversalAIRequestDto request) {
        String contextText = request.getPrompt() != null ? request.getPrompt() : 
                           request.getSelectedText() != null ? request.getSelectedText() : "";
        
        return novelSettingService.findRelevantSettings(
                request.getNovelId(), 
                contextText, 
                request.getSceneId(), 
                null, 
                5
        )
        .collectList()
        .map(settings -> {
            if (settings.isEmpty()) {
                return "";
            }
            
            StringBuilder context = new StringBuilder();
            context.append("=== 相关设定 ===\n");
            for (NovelSettingItem setting : settings) {
                context.append("- ").append(setting.getName())
                       .append("(").append(setting.getType()).append("): ")
                       .append(setting.getDescription()).append("\n");
            }
            return context.toString();
        })
        .onErrorReturn("");
    }

    /**
     * 🚀 新增：获取智能检索内容（RAG检索上下文）
     */
    private Mono<String> getSmartRetrievalContent(UniversalAIRequestDto request) {
        // 🚀 检查是否启用智能上下文（RAG检索）
        Boolean enableSmartContext = (Boolean) request.getMetadata().get("enableSmartContext");
        // 如果 metadata 中没有，则从 parameters 中回退读取
        if (enableSmartContext == null && request.getParameters() != null) {
            Object flag = request.getParameters().get("enableSmartContext");
            if (flag instanceof Boolean) {
                enableSmartContext = (Boolean) flag;
            }
        }
        if (enableSmartContext == null || !enableSmartContext) {
            log.info("智能上下文未启用，跳过RAG检索");
            return Mono.just("");
        }
        
        AIFeatureType featureType = mapRequestTypeToFeatureType(request.getRequestType());
        
        return ragService.retrieveRelevantContext(
                request.getNovelId(), 
                request.getSceneId(), 
                request.getPrompt(), 
                featureType
        )
        .map(context -> {
            if (context == null || context.isEmpty()) {
                return "";
            }
            return "=== RAG检索结果 ===\n" + context;
        })
        .doOnSuccess(context -> {
            if (!context.isEmpty()) {
                log.info("RAG检索成功，获得上下文长度: {} 字符", context.length());
            } else {
                log.info("RAG检索未找到相关上下文");
            }
        })
        .onErrorReturn("");
    }

    /**
     * 🚀 新增：生成并存储提示词预设（供内部服务调用）
     */
    @Override
    public Mono<PromptGenerationResult> generateAndStorePrompt(UniversalAIRequestDto request) {
        log.info("开始生成并存储提示词预设 - 用户ID: {}, 请求类型: {}", request.getUserId(), request.getRequestType());
        
        return Mono.fromCallable(() -> {
            // 1. 计算配置哈希
            String configHash = calculateConfigHash(request);
            log.debug("计算的配置哈希: {}", configHash);
            return configHash;
        })
        .flatMap((String configHash) -> {
            // 2. 查重：检查是否已存在相同配置
            return promptPresetRepository.findByUserIdAndPresetHash(request.getUserId(), configHash)
                    .cast(AIPromptPreset.class)
                    .flatMap((AIPromptPreset existingPreset) -> {
                        // 如果找到现有预设，直接返回
                        log.info("找到现有配置预设: {}", existingPreset.getPresetId());
                        return Mono.just(new PromptGenerationResult(
                                existingPreset.getPresetId(),
                                existingPreset.getSystemPrompt(),
                                existingPreset.getUserPrompt(),
                                existingPreset.getPresetHash()
                        ));
                    })
                    .switchIfEmpty(generateNewPromptPreset(request, configHash));
        })
        .doOnSuccess(result -> log.info("提示词预设生成完成 - presetId: {}", result.getPresetId()))
        .doOnError(error -> log.error("生成提示词预设失败: {}", error.getMessage(), error));
    }

    /**
     * 生成新的提示词预设
     */
    private Mono<PromptGenerationResult> generateNewPromptPreset(UniversalAIRequestDto request, String configHash) {
        Mono<String> contextDataMono = getContextData(request).cache();
        return Mono.zip(
                getSystemPrompt(request, contextDataMono),
                getUserPrompt(request, contextDataMono)
        ).flatMap(tuple -> {
            String systemPrompt = tuple.getT1();
            String userPrompt = tuple.getT2();
            
            // 🚀 修复：添加null值检查和验证
            String userId = request.getUserId();
            if (userId == null || userId.trim().isEmpty()) {
                return Mono.error(new IllegalArgumentException("用户ID不能为空"));
            }
            
            if (configHash == null || configHash.trim().isEmpty()) {
                return Mono.error(new IllegalStateException("配置哈希计算失败，不能为空"));
            }
            
            // 创建新的预设实体
            String presetId = UUID.randomUUID().toString();
            AIPromptPreset preset = AIPromptPreset.builder()
                    .presetId(presetId)
                    .userId(userId)
                    .novelId(request.getNovelId()) // 🚀 新增：设置novelId
                    .presetHash(configHash)
                    .requestData(serializeRequestData(request))
                    .systemPrompt(systemPrompt != null ? systemPrompt : "")
                    .userPrompt(userPrompt != null ? userPrompt : "")
                    .aiFeatureType(request.getRequestType() != null ? request.getRequestType().toUpperCase() : "CHAT")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            
            // 保存到数据库
            return promptPresetRepository.save(preset)
                    .map(savedPreset -> {
                        log.info("新提示词预设保存成功: {}", savedPreset.getPresetId());
                        return new PromptGenerationResult(
                                savedPreset.getPresetId(),
                                savedPreset.getSystemPrompt(),
                                savedPreset.getUserPrompt(),
                                savedPreset.getPresetHash()
                        );
                    });
        });
    }

    /**
     * 计算配置哈希值
     */
    private String calculateConfigHash(UniversalAIRequestDto request) {
        try {
            // 🚀 修复：添加请求参数验证
            if (request == null) {
                throw new IllegalArgumentException("请求参数不能为空");
            }
            
            StringBuilder hashInput = new StringBuilder();
            
            // 包含影响提示词生成的关键字段
            hashInput.append("requestType:").append(request.getRequestType() != null ? request.getRequestType() : "unknown").append("|");
            hashInput.append("instructions:").append(request.getInstructions() != null ? request.getInstructions() : "").append("|");
            
            // 包含sessionId（如果有）
            if (request.getSessionId() != null && !request.getSessionId().isEmpty()) {
                hashInput.append("sessionId:").append(request.getSessionId()).append("|");
            }
            
            // 从参数中获取智能上下文设置
            boolean enableSmartContext = false;
            if (request.getParameters() != null) {
                Object smartContextObj = request.getParameters().get("enableSmartContext");
                enableSmartContext = smartContextObj instanceof Boolean ? (Boolean) smartContextObj : false;
            }
            hashInput.append("enableSmartContext:").append(enableSmartContext).append("|");
            
            // 上下文选择（如果有）
            if (request.getContextSelections() != null && !request.getContextSelections().isEmpty()) {
                List<String> sortedSelections = request.getContextSelections().stream()
                        .map(selection -> selection.getId() + ":" + selection.getType())
                        .sorted()
                        .collect(Collectors.toList());
                hashInput.append("contextSelections:").append(String.join(",", sortedSelections)).append("|");
            }
            
            // 参数（如果有）
            if (request.getParameters() != null) {
                Object temperature = request.getParameters().get("temperature");
                Object maxTokens = request.getParameters().get("maxTokens");
                Object memoryCutoff = request.getParameters().get("memoryCutoff");
                
                if (temperature != null) hashInput.append("temperature:").append(temperature).append("|");
                if (maxTokens != null) hashInput.append("maxTokens:").append(maxTokens).append("|");
                if (memoryCutoff != null) hashInput.append("memoryCutoff:").append(memoryCutoff).append("|");
            }
            
            // 计算SHA-256哈希
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(hashInput.toString().getBytes(StandardCharsets.UTF_8));
            
            // 转换为十六进制字符串
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            String result = hexString.toString();
            
            // 🚀 修复：最后的保护措施，确保哈希值不为空
            if (result == null || result.trim().isEmpty()) {
                String fallbackHash = "fallback_" + System.currentTimeMillis() + "_" + request.hashCode();
                log.warn("计算的哈希值为空，使用后备哈希: {}", fallbackHash);
                return fallbackHash;
            }
            
            return result;
        } catch (NoSuchAlgorithmException e) {
            log.error("计算哈希时发生错误", e);
            throw new RuntimeException("计算配置哈希失败", e);
        } catch (Exception e) {
            // 🚀 修复：捕获所有异常，提供后备哈希
            String fallbackHash = "emergency_" + System.currentTimeMillis() + "_" + (request != null ? request.hashCode() : 0);
            log.error("计算配置哈希时发生意外错误，使用紧急后备哈希: {}", fallbackHash, e);
            return fallbackHash;
        }
    }

    /**
     * 序列化请求数据为JSON字符串
     */
    private String serializeRequestData(UniversalAIRequestDto request) {
        try {
            // 使用ObjectMapper进行JSON序列化
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.registerModule(new JavaTimeModule());
            objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            
            Map<String, Object> data = new HashMap<>();
            data.put("requestType", request.getRequestType());
            data.put("userId", request.getUserId());
            data.put("novelId", request.getNovelId());
            data.put("sessionId", request.getSessionId());
            data.put("instructions", request.getInstructions());
            // 从参数中获取智能上下文设置
            boolean enableSmartContext = false;
            if (request.getParameters() != null) {
                Object smartContextObj = request.getParameters().get("enableSmartContext");
                enableSmartContext = smartContextObj instanceof Boolean ? (Boolean) smartContextObj : false;
            }
            data.put("enableSmartContext", enableSmartContext);
            data.put("parameters", request.getParameters());
            data.put("contextSelections", request.getContextSelections());
            data.put("metadata", request.getMetadata());
            
            // 使用Jackson进行JSON序列化
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.error("序列化请求数据失败", e);
            return "{}";
        }
    }

    @Override
    public Mono<AIPromptPreset> getPromptPresetById(String presetId) {
        log.info("根据预设ID获取AI提示词预设: {}", presetId);
        
        return promptPresetRepository.findByPresetId(presetId)
                .flatMap(preset -> {
                    if (preset != null) {
                        // 🚀 检查并修复错误格式的requestData
                        return fixCorruptedRequestData(preset);
                    }
                    return Mono.just(preset);
                })
                .doOnSuccess(preset -> {
                    if (preset != null) {
                        log.info("找到AI提示词预设: presetId={}, userId={}", preset.getPresetId(), preset.getUserId());
                    } else {
                        log.warn("未找到AI提示词预设: presetId={}", presetId);
                    }
                })
                .doOnError(error -> log.error("获取AI提示词预设失败: presetId={}, error={}", presetId, error.getMessage()));
    }

    // 🚀 新增：扩展预设管理功能实现

    @Override
    public Mono<AIPromptPreset> createNamedPreset(UniversalAIRequestDto request, String presetName, 
                                                 String presetDescription, java.util.List<String> presetTags) {
        log.info("创建命名预设 - userId: {}, presetName: {}", request.getUserId(), presetName);
        
        // 检查预设名称是否已存在
        return promptPresetRepository.existsByUserIdAndPresetName(request.getUserId(), presetName)
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new IllegalArgumentException("预设名称已存在: " + presetName));
                    }
                    
                    // 生成提示词预设
                    return generateAndStorePrompt(request)
                            .flatMap(result -> {
                                // 更新预设信息
                                return promptPresetRepository.findByPresetId(result.getPresetId())
                                        .flatMap(preset -> {
                                            preset.setPresetName(presetName);
                                            preset.setPresetDescription(presetDescription);
                                            preset.setPresetTags(presetTags);
                                            preset.setUpdatedAt(LocalDateTime.now());
                                            return promptPresetRepository.save(preset);
                                        });
                            });
                });
    }

    @Override
    public Mono<AIPromptPreset> updatePresetInfo(String presetId, String presetName, 
                                               String presetDescription, java.util.List<String> presetTags) {
        log.info("更新预设信息 - presetId: {}, presetName: {}", presetId, presetName);
        
        return promptPresetRepository.findByPresetId(presetId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("预设不存在: " + presetId)))
                .flatMap(preset -> {
                    // 如果名称发生变化，检查新名称是否已存在
                    if (!presetName.equals(preset.getPresetName())) {
                        return promptPresetRepository.existsByUserIdAndPresetName(preset.getUserId(), presetName)
                                .flatMap(exists -> {
                                    if (exists) {
                                        return Mono.error(new IllegalArgumentException("预设名称已存在: " + presetName));
                                    }
                                    return updatePresetFields(preset, presetName, presetDescription, presetTags);
                                });
                    } else {
                        return updatePresetFields(preset, presetName, presetDescription, presetTags);
                    }
                });
    }

    private Mono<AIPromptPreset> updatePresetFields(AIPromptPreset preset, String presetName, 
                                                   String presetDescription, java.util.List<String> presetTags) {
        preset.setPresetName(presetName);
        preset.setPresetDescription(presetDescription);
        preset.setPresetTags(presetTags);
        preset.setUpdatedAt(LocalDateTime.now());
        return promptPresetRepository.save(preset);
    }

    @Override
    public Mono<AIPromptPreset> updatePresetPrompts(String presetId, String customSystemPrompt, String customUserPrompt) {
        log.info("更新预设提示词 - presetId: {}", presetId);
        
        return promptPresetRepository.findByPresetId(presetId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("预设不存在: " + presetId)))
                .flatMap(preset -> {
                    preset.setCustomSystemPrompt(customSystemPrompt);
                    preset.setCustomUserPrompt(customUserPrompt);
                    preset.setPromptCustomized(true);
                    preset.setUpdatedAt(LocalDateTime.now());
                    return promptPresetRepository.save(preset);
                });
    }

    @Override
    public Flux<AIPromptPreset> getUserPresets(String userId) {
        log.info("获取用户所有预设 - userId: {}", userId);
        return promptPresetRepository.findByUserIdOrderByLastUsedAtDesc(userId);
    }

    @Override
    public Flux<AIPromptPreset> getUserPresetsByNovelId(String userId, String novelId) {
        log.info("根据小说ID获取用户预设 - userId: {}, novelId: {}", userId, novelId);
        return promptPresetRepository.findByUserIdAndNovelIdOrderByLastUsedAtDesc(userId, novelId);
    }

    @Override
    public Flux<AIPromptPreset> getUserPresetsByFeatureType(String userId, String featureType) {
        log.info("根据功能类型获取用户预设 - userId: {}, featureType: {}", userId, featureType);
        return promptPresetRepository.findByUserIdAndAiFeatureType(userId, featureType);
    }

    @Override
    public Flux<AIPromptPreset> getUserPresetsByFeatureTypeAndNovelId(String userId, String featureType, String novelId) {
        log.info("根据功能类型和小说ID获取用户预设 - userId: {}, featureType: {}, novelId: {}", userId, featureType, novelId);
        return promptPresetRepository.findByUserIdAndAiFeatureTypeAndNovelId(userId, featureType, novelId);
    }

    @Override
    public Flux<AIPromptPreset> searchUserPresets(String userId, String keyword, 
                                                 java.util.List<String> tags, String featureType) {
        log.info("搜索用户预设 - userId: {}, keyword: {}, tags: {}, featureType: {}", userId, keyword, tags, featureType);
        return promptPresetRepository.searchPresets(userId, keyword, tags, featureType);
    }

    @Override
    public Flux<AIPromptPreset> searchUserPresetsByNovelId(String userId, String keyword, 
                                                          java.util.List<String> tags, String featureType, String novelId) {
        log.info("根据小说ID搜索用户预设 - userId: {}, keyword: {}, tags: {}, featureType: {}, novelId: {}", 
                userId, keyword, tags, featureType, novelId);
        return promptPresetRepository.searchPresetsByNovelId(userId, keyword, tags, featureType, novelId);
    }

    @Override
    public Flux<AIPromptPreset> getUserFavoritePresets(String userId) {
        log.info("获取用户收藏预设 - userId: {}", userId);
        return promptPresetRepository.findByUserIdAndIsFavoriteTrue(userId);
    }

    @Override
    public Flux<AIPromptPreset> getUserFavoritePresetsByNovelId(String userId, String novelId) {
        log.info("根据小说ID获取用户收藏预设 - userId: {}, novelId: {}", userId, novelId);
        return promptPresetRepository.findByUserIdAndIsFavoriteTrueAndNovelId(userId, novelId);
    }

    @Override
    public Mono<AIPromptPreset> togglePresetFavorite(String presetId) {
        log.info("切换预设收藏状态 - presetId: {}", presetId);
        
        return promptPresetRepository.findByPresetId(presetId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("预设不存在: " + presetId)))
                .flatMap(preset -> {
                    preset.setIsFavorite(!preset.getIsFavorite());
                    preset.setUpdatedAt(LocalDateTime.now());
                    return promptPresetRepository.save(preset);
                });
    }

    @Override
    public Mono<Void> deletePreset(String presetId) {
        log.info("删除预设 - presetId: {}", presetId);
        return promptPresetRepository.deleteByPresetId(presetId);
    }

    @Override
    public Mono<AIPromptPreset> duplicatePreset(String presetId, String newPresetName) {
        log.info("复制预设 - sourcePresetId: {}, newPresetName: {}", presetId, newPresetName);
        
        return promptPresetRepository.findByPresetId(presetId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("源预设不存在: " + presetId)))
                .flatMap(sourcePreset -> {
                    // 检查新名称是否已存在
                    return promptPresetRepository.existsByUserIdAndPresetName(sourcePreset.getUserId(), newPresetName)
                            .flatMap(exists -> {
                                if (exists) {
                                    return Mono.error(new IllegalArgumentException("预设名称已存在: " + newPresetName));
                                }
                                
                                // 创建复制的预设
                                String newPresetId = UUID.randomUUID().toString();
                                AIPromptPreset newPreset = AIPromptPreset.builder()
                                        .presetId(newPresetId)
                                        .userId(sourcePreset.getUserId())
                                        .novelId(sourcePreset.getNovelId()) // 🚀 新增：复制novelId
                                        .presetName(newPresetName)
                                        .presetDescription(sourcePreset.getPresetDescription() + " (复制)")
                                        .presetTags(sourcePreset.getPresetTags())
                                        .isFavorite(false)
                                        .isPublic(false)
                                        .useCount(0)
                                        .presetHash(sourcePreset.getPresetHash())
                                        .requestData(sourcePreset.getRequestData())
                                        .systemPrompt(sourcePreset.getSystemPrompt())
                                        .userPrompt(sourcePreset.getUserPrompt())
                                        .aiFeatureType(sourcePreset.getAiFeatureType())
                                        .customSystemPrompt(sourcePreset.getCustomSystemPrompt())
                                        .customUserPrompt(sourcePreset.getCustomUserPrompt())
                                        .promptCustomized(sourcePreset.getPromptCustomized())
                                        .createdAt(LocalDateTime.now())
                                        .updatedAt(LocalDateTime.now())
                                        .build();
                                
                                return promptPresetRepository.save(newPreset);
                            });
                });
    }

    @Override
    public Mono<AIPromptPreset> recordPresetUsage(String presetId) {
        log.info("记录预设使用 - presetId: {}", presetId);
        
        return promptPresetRepository.findByPresetId(presetId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("预设不存在: " + presetId)))
                .flatMap(preset -> {
                    preset.incrementUseCount();
                    return promptPresetRepository.save(preset);
                });
    }

    @Override
    public Mono<PresetStatistics> getPresetStatistics(String userId) {
        log.info("获取预设统计信息 - userId: {}", userId);
        
        // 并行获取各项统计
        Mono<Long> totalMono = promptPresetRepository.countByUserId(userId);
        Mono<Long> favoriteMono = promptPresetRepository.countByUserIdAndIsFavoriteTrue(userId);
        Mono<Long> recentMono = promptPresetRepository.findRecentlyUsedPresets(userId, LocalDateTime.now().minusDays(30))
                .count();
        
        return Mono.zip(totalMono, favoriteMono, recentMono)
                .map(tuple -> {
                    int total = tuple.getT1().intValue();
                    int favorite = tuple.getT2().intValue();
                    int recent = tuple.getT3().intValue();
                    
                    // TODO: 实现按功能类型统计和热门标签统计
                    Map<String, Integer> byFeatureType = new HashMap<>();
                    List<String> popularTags = new ArrayList<>();
                    
                    return new PresetStatistics(total, favorite, recent, byFeatureType, popularTags);
                });
    }

    @Override
    public Mono<PresetStatistics> getPresetStatisticsByNovelId(String userId, String novelId) {
        log.info("根据小说ID获取预设统计信息 - userId: {}, novelId: {}", userId, novelId);
        
        // 并行获取各项统计
        Mono<Long> totalMono = promptPresetRepository.countByUserIdAndNovelId(userId, novelId);
        Mono<Long> favoriteMono = promptPresetRepository.countByUserIdAndIsFavoriteTrueAndNovelId(userId, novelId);
        Mono<Long> recentMono = promptPresetRepository.findByUserIdAndNovelIdOrderByLastUsedAtDesc(userId, novelId)
                .filter(preset -> preset.getLastUsedAt() != null && 
                        preset.getLastUsedAt().isAfter(LocalDateTime.now().minusDays(30)))
                .count();
        
        return Mono.zip(totalMono, favoriteMono, recentMono)
                .map(tuple -> {
                    int total = tuple.getT1().intValue();
                    int favorite = tuple.getT2().intValue();
                    int recent = tuple.getT3().intValue();
                    
                    // TODO: 实现按功能类型统计和热门标签统计
                    Map<String, Integer> byFeatureType = new HashMap<>();
                    List<String> popularTags = new ArrayList<>();
                    
                    return new PresetStatistics(total, favorite, recent, byFeatureType, popularTags);
                });
    }

    /**
     * 🚀 修复损坏的requestData（如果是Java对象toString格式）
     */
    private Mono<AIPromptPreset> fixCorruptedRequestData(AIPromptPreset preset) {
        String requestData = preset.getRequestData();
        
        // 检查是否为Java对象toString格式
        if (requestData != null && requestData.startsWith("{") && 
            requestData.contains("ContextSelectionDto(") && !requestData.contains("\"")) {
            
            log.warn("检测到损坏的requestData格式，删除预设: presetId={}", preset.getPresetId());
            
            // 删除损坏的预设，让系统重新生成
            return promptPresetRepository.delete(preset)
                    .then(Mono.empty()); // 返回empty，触发重新生成
        }
        
        // 数据格式正常，直接返回
        return Mono.just(preset);
    }

    /**
     * 异步去重：调用 NovelService 缓存索引，避免阻塞。
     */
    private Mono<List<UniversalAIRequestDto.ContextSelectionDto>> preprocessAndDeduplicateSelectionsAsync(
            List<UniversalAIRequestDto.ContextSelectionDto> selections, String novelId) {

        if (selections == null || selections.isEmpty()) {
            return Mono.just(Collections.emptyList());
        }

        // 🚀 快速路径：当仅包含局部型上下文（不涉及层级覆盖关系）时，跳过全书级包含索引构建
        if (!requiresContainIndex(selections)) {
            return Mono.just(preprocessWithoutIndex(selections));
        }

        // 仅当需要处理层级覆盖关系时，才构建/读取包含索引
        return novelService.getContainIndex(novelId)
                .defaultIfEmpty(new NovelStructureCache.ContainIndex(Collections.emptyMap()))
                .map(index -> preprocessWithIndex(selections, index));
    }

    /**
     * 判断是否需要依赖包含索引（存在层级覆盖关系的类型）
     */
    private boolean requiresContainIndex(List<UniversalAIRequestDto.ContextSelectionDto> selections) {
        for (UniversalAIRequestDto.ContextSelectionDto sel : selections) {
            if (sel == null || sel.getType() == null) {
                continue;
            }
            String type = sel.getType().toLowerCase();
            // 这些类型会产生上/下层级覆盖关系，需要索引支持
            if ("full_novel_text".equals(type)
                    || "full_novel_summary".equals(type)
                    || "act".equals(type)
                    || "chapter".equals(type)
                    || "previous_chapters_content".equals(type)
                    || "previous_chapters_summary".equals(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 无索引的快速去重：
     * - 仅去重完全相同的内容（按标准化后的 type/id 唯一）
     * - 保持原有类型优先级排序
     */
    private List<UniversalAIRequestDto.ContextSelectionDto> preprocessWithoutIndex(
            List<UniversalAIRequestDto.ContextSelectionDto> selections) {

        log.info("跳过包含索引构建，执行快速去重。原始选择数量: {}", selections.size());

        // 按类型优先级排序，复用现有优先级策略
        List<UniversalAIRequestDto.ContextSelectionDto> sorted = selections.stream()
                .sorted(Comparator.comparingInt(s -> getTypePriority(s.getType())))
                .toList();

        List<UniversalAIRequestDto.ContextSelectionDto> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (var sel : sorted) {
            if (sel == null) continue;
            String normId = normalizeId(sel.getType(), sel.getId());
            if (seen.contains(normId)) {
                continue;
            }
            result.add(sel);
            seen.add(normId);
        }

        log.info("快速去重完成，优化后选择数量: {} (原始: {})", result.size(), selections.size());
        return result;
    }

    /**
     * 纯计算：根据 ContainIndex 去重，无任何 I/O。
     */
    private List<UniversalAIRequestDto.ContextSelectionDto> preprocessWithIndex(
            List<UniversalAIRequestDto.ContextSelectionDto> selections,
            NovelStructureCache.ContainIndex index) {

        log.info("开始预处理去重，原始选择数量: {}", selections.size());

        // 排序
        List<UniversalAIRequestDto.ContextSelectionDto> sorted = selections.stream()
                .sorted(Comparator.comparingInt(s -> getTypePriority(s.getType())))
                .toList();

        List<UniversalAIRequestDto.ContextSelectionDto> result = new ArrayList<>();
        Set<String> excluded = new HashSet<>();

        for (var sel : sorted) {
            String normId = normalizeId(sel.getType(), sel.getId());
            if (excluded.contains(normId)) {
                continue;
            }
            result.add(sel);
            // 自己也算排除
            excluded.add(normId);
            // 添加其覆盖集
            excluded.addAll(index.getContained(normId));
        }

        log.info("预处理去重完成，优化后选择数量: {} (原始: {})", result.size(), selections.size());
        return result;
    }






    /**
     * 从AIRequest参数/元数据中提取或生成idempotencyKey
     */
    @SuppressWarnings("unchecked")
    private String getOrCreateIdempotencyKey(AIRequest aiRequest) {
        try {
            if (aiRequest.getParameters() != null) {
                Object psRaw = aiRequest.getParameters().get("providerSpecific");
                if (psRaw instanceof java.util.Map<?, ?> m) {
                    Object key = m.get(com.ainovel.server.service.billing.BillingKeys.REQUEST_IDEMPOTENCY_KEY);
                    if (key != null) return key.toString();
                }
            }
            if (aiRequest.getMetadata() != null) {
                Object key = aiRequest.getMetadata().get(com.ainovel.server.service.billing.BillingKeys.REQUEST_IDEMPOTENCY_KEY);
                if (key != null) return key.toString();
            }
        } catch (Exception ignore) {}
        return java.util.UUID.randomUUID().toString();
    }

} 