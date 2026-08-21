package ai.cc.chongming.review.infrastructure.notification;

import ai.cc.chongming.review.application.NotificationDeliveryException;
import ai.cc.chongming.review.config.ChaoxingNotificationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * [AIREVIEW-PLAN-030] Chaoxing (学习通) uid-based notice sender, adapted from the reference
 * {@code sendNotice} implementation to the project's HTTP/JSON stack (RestTemplate + Jackson, no
 * hutool/fastjson). The official request signing ({@code fillParams_encnew}: {@code _time}+
 * {@code token}+MD5 over key-sorted params with {@code _key}) is implemented in
 * {@link #fillParamsEncNew}; the channel stays disabled by configuration until endpoint and
 * credentials are supplied.
 *
 * @author wangli
 */
@Component
public class ChaoxingNoticeClient {

    private static final Logger log = LoggerFactory.getLogger(ChaoxingNoticeClient.class);

    private final ChaoxingNotificationProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public ChaoxingNoticeClient(ChaoxingNotificationProperties properties) {
        this(properties, new RestTemplate(), new ObjectMapper());
    }

    public ChaoxingNoticeClient(
            ChaoxingNotificationProperties properties, RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /** Result of one Chaoxing notice submission. */
    public record NoticeResult(int result, String msg, String msgid, String url,
                               String requestContent, String responseContent) {
    }

    /**
     * Sends a notice to the given Chaoxing uids. Mirrors the reference {@code sendNotice} parameter
     * assembly (puid/title/content/topuids/sourceUrl/pcode) without the calendar/rtf attachment
     * branch, which the review notification does not use.
     */
    public NoticeResult sendNotice(List<Integer> users, String title, String content, String sourceUrl) {
        requireConfigured();
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("puid", properties.puid());
        params.add("title", title);
        params.add("content", content);
        if (users != null && !users.isEmpty()) {
            params.add("topuids", String.join(",", users.stream().map(String::valueOf).toList()));
        }
        if (sourceUrl != null && !sourceUrl.isBlank()) {
            params.add("sourceUrl", sourceUrl);
        }
        params.add("pcode", properties.pcode());

        Map<String, Object> signed = fillParamsEncNew(properties.api(), params.toSingleValueMap());

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        signed.forEach(form::add);

        NoticeResult notice = new NoticeResult(0, null, null, null,
                writeJson(signed), null);
        try {
            String body = restTemplate.postForObject(
                    properties.api(), form, String.class, MediaType.APPLICATION_FORM_URLENCODED);
            return parseResponse(notice, body);
        } catch (RuntimeException exception) {
            log.error("Chaoxing notice transport error: {}", exception.getMessage(), exception);
            throw new NotificationDeliveryException("CHAOXING_TRANSPORT_ERROR", true,
                    "Chaoxing notice invocation failed", exception);
        }
    }

    private NoticeResult parseResponse(NoticeResult base, String body) {
        try {
            var node = objectMapper.readTree(body == null ? "{}" : body);
            String idCode = node.path("data").path("idCode").asText("");
            String url = idCode.isBlank()
                    ? null : properties.detailUrl().replace("{{}}", idCode);
            return new NoticeResult(
                    node.path("result").asInt(0),
                    node.path("msg").asText(null),
                    node.path("data").path("id").asText(null),
                    url,
                    base.requestContent(),
                    body);
        } catch (Exception exception) {
            throw new NotificationDeliveryException("CHAOXING_RESPONSE_INVALID", false,
                    "Chaoxing notice response is not valid JSON", exception);
        }
    }

    private void requireConfigured() {
        if (properties.api().isBlank() || properties.puid().isBlank() || properties.pcode().isBlank()) {
            throw new NotificationDeliveryException("CHAOXING_UNCONFIGURED", false,
                    "Chaoxing notice api/puid/pcode are not configured (review.notification.chaoxing.*)");
        }
        if (properties.token().isBlank() || properties.key().isBlank()) {
            throw new NotificationDeliveryException("CHAOXING_SIGNING_UNCONFIGURED", false,
                    "Chaoxing signing token/key are not configured (review.notification.chaoxing.token/key)");
        }
    }

    /**
     * Official {@code fillParams_encnew} signing: inject {@code _time} and {@code token}, then append
     * {@code inf_enc} = MD5 over the key-sorted {@code k=v&...} parameter string plus {@code &_key=}.
     */
    private Map<String, Object> fillParamsEncNew(String api, Map<String, String> singleValueParams) {
        Map<String, String> signed = new LinkedHashMap<>(singleValueParams);
        signed.put("_time", String.valueOf(System.currentTimeMillis()));
        signed.put("token", properties.token());
        StringBuilder signData = new StringBuilder();
        signed.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> signData.append(entry.getKey()).append('=').append(entry.getValue()).append('&'));
        if (signData.length() > 0) {
            signData.setLength(signData.length() - 1);
        }
        signData.append("&_key=").append(properties.key());
        String infEnc = md5Hex(signData.toString());
        signed.put("inf_enc", infEnc);
        return new LinkedHashMap<>(signed);
    }

    private static String md5Hex(String payload) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 is unavailable", exception);
        }
    }

    private String writeJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception exception) {
            return String.valueOf(map);
        }
    }
}
