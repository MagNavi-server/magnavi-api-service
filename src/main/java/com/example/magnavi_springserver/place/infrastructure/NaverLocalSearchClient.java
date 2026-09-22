package com.example.magnavi_springserver.place.infrastructure;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.example.magnavi_springserver.place.application.PlaceSearchException;
import com.example.magnavi_springserver.place.application.PlaceSearchResult;

/** NAVER API HUB를 호출하고 업체 정보만 앱의 검색 결과로 변환한다. DB에는 접근하지 않는다. */
@Component
public class NaverLocalSearchClient {

    private static final URI API = URI.create("https://naverapihub.apigw.ntruss.com/search/v1/local");
    private final HttpClient http;
    private final ObjectMapper json;
    private final NaverSearchProperties properties;
    private final URI endpoint;

    /** 운영에서는 고정된 HTTPS 주소만 사용해 임의 서버에 검색용 키가 전달되지 않게 한다. */
    @Autowired
    public NaverLocalSearchClient(@Qualifier("naverSearchHttpClient") HttpClient http,
                                  ObjectMapper json, NaverSearchProperties properties) {
        this(http, json, properties, API);
    }

    /** 같은 패키지의 테스트만 임시 로컬 HTTP 서버 주소를 주입할 수 있다. */
    NaverLocalSearchClient(HttpClient http, ObjectMapper json, NaverSearchProperties properties, URI endpoint) {
        this.http = http;
        this.json = json;
        this.properties = properties;
        this.endpoint = endpoint;
    }

    /** 준비되지 않은 검색 설정 때문에 다른 회원·실내 장소 API까지 중단하지 않는다. */
    public void requireConfigured() {
        if (!properties.isReady()) {
            throw new PlaceSearchException(PlaceSearchException.Reason.NOT_CONFIGURED);
        }
    }

    /** 헤더와 본문을 모두 받는 시간을 제한한다. 실패 시 애플리케이션에서 재시도하지 않는다. */
    public PlaceSearchResult search(String query, int display, String sort) {
        requireConfigured();
        URI uri = UriComponentsBuilder.fromUri(endpoint)
                .queryParam("query", "{query}").queryParam("display", display)
                .queryParam("start", 1).queryParam("sort", sort).queryParam("format", "json")
                .encode().buildAndExpand(query).toUri();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(properties.requestTimeoutMs()))
                .header("X-NCP-APIGW-API-KEY-ID", properties.clientId())
                .header("X-NCP-APIGW-API-KEY", properties.clientSecret())
                .header("Accept", "application/json").GET().build();
        var pending = http.sendAsync(request, response -> new LimitedSearchBodySubscriber());
        try {
            HttpResponse<byte[]> response = pending.get(properties.requestTimeoutMs(), TimeUnit.MILLISECONDS);
            if (response.statusCode() == 429) {
                throw new PlaceSearchException(PlaceSearchException.Reason.PROVIDER_LIMITED);
            }
            if (response.statusCode() != 200) {
                throw providerError();
            }
            return parse(response.body(), display);
        } catch (TimeoutException exception) {
            throw new PlaceSearchException(PlaceSearchException.Reason.TIMEOUT);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PlaceSearchException(PlaceSearchException.Reason.BUSY);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof HttpTimeoutException) {
                throw new PlaceSearchException(PlaceSearchException.Reason.TIMEOUT);
            }
            throw providerError();
        } finally {
            // 전체 제한 시간이 끝났으면 본문이 조금씩 도착하고 있어도 수신을 취소한다.
            pending.cancel(true);
        }
    }

    /** 잘못된 응답을 빈 검색 결과로 오인하지 않는다. 항목 순서는 네이버가 준 그대로 유지한다. */
    private PlaceSearchResult parse(byte[] body, int display) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode items = root == null ? null : root.get("items");
            if (items == null || !items.isArray() || items.size() > display) {
                throw providerError();
            }
            var results = new ArrayList<PlaceSearchResult.Item>();
            for (JsonNode item : items) {
                String name = plainText(text(item, "title"));
                if (name.isBlank()) {
                    throw providerError();
                }
                results.add(new PlaceSearchResult.Item(name, plainText(text(item, "category")),
                        plainText(text(item, "address")), plainText(text(item, "roadAddress")),
                        safeLink(text(item, "link")), coordinate(text(item, "mapy"), 90),
                        coordinate(text(item, "mapx"), 180)));
            }
            return new PlaceSearchResult("NAVER", results);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw providerError();
        }
    }

    /** 선택 표시 필드의 누락은 빈 문자열로 표현하되 문자열이 아닌 값은 거절한다. */
    private String text(JsonNode item, String field) {
        JsonNode value = item.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        if (!value.isString() || value.asString().length() > 4096) {
            throw providerError();
        }
        return value.asString();
    }

    /** 강조용 b 태그와 HTML 문자를 표시용 문자열로 변환한다. 앱은 HTML로 렌더링하지 않는다. */
    private String plainText(String value) {
        return HtmlUtils.htmlUnescape(value.replaceAll("(?i)</?b>", ""));
    }

    /** 업체 링크는 HTTP(S)만 허용한다. 링크를 서버에서 열거나 식별자로 사용하지 않는다. */
    private String safeLink(String value) {
        if (value.isBlank()) {
            return "";
        }
        URI link = URI.create(value);
        if (link.getHost() == null || link.getUserInfo() != null
                || !("https".equalsIgnoreCase(link.getScheme()) || "http".equalsIgnoreCase(link.getScheme()))) {
            throw providerError();
        }
        return value;
    }

    /** 명시한 단위로만 변환한다. NaN·무한대·범위 밖 좌표나 서로 다른 단위를 숨기지 않는다. */
    private BigDecimal coordinate(String value, int maximum) {
        String pattern = properties.coordinateFormat() == NaverSearchProperties.CoordinateFormat.E7
                ? "-?[0-9]{1,10}" : "-?[0-9]{1,3}(\\.[0-9]{1,10})?";
        if (!value.matches(pattern)) {
            throw providerError();
        }
        BigDecimal number = new BigDecimal(value);
        if (properties.coordinateFormat() == NaverSearchProperties.CoordinateFormat.E7) {
            number = number.movePointLeft(7);
        }
        if (number.abs().compareTo(BigDecimal.valueOf(maximum)) > 0) {
            throw providerError();
        }
        return number;
    }

    /** 외부 URL·오류 본문·키를 담지 않는 일정한 실패 객체를 만든다. */
    private PlaceSearchException providerError() {
        return new PlaceSearchException(PlaceSearchException.Reason.PROVIDER_ERROR);
    }
}
