import 'dart:convert';
import 'dart:io';

/// API client for a separately deployed, authenticated LUMIN backend.
/// Never place model-provider secrets in a mobile app.
class StudioApi {
  StudioApi({required this.baseUrl, required this.accessToken});
  final Uri baseUrl;
  final String accessToken;

  Future<String> createJob({
    required String type,
    required String prompt,
    required String aspectRatio,
  }) async {
    if (accessToken.isEmpty) throw const StudioApiException('Inicia sessão antes de gerar.');
    if (!['image', 'video'].contains(type)) throw const StudioApiException('Tipo inválido.');
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 15);
    try {
      final uri = baseUrl.resolve('/v1/generation/jobs');
      if (uri.scheme != 'https') throw const StudioApiException('O backend deve usar HTTPS.');
      final req = await client.postUrl(uri);
      req.headers.contentType = ContentType.json;
      req.headers.set(HttpHeaders.authorizationHeader, 'Bearer $accessToken');
      req.add(utf8.encode(jsonEncode({
        'type': type,
        'prompt': prompt,
        'aspect_ratio': aspectRatio,
      })));
      final response = await req.close().timeout(const Duration(seconds: 30));
      final raw = await utf8.decoder.bind(response).join();
      if (response.statusCode != 202) {
        throw StudioApiException('Falha no pedido (${response.statusCode}).');
      }
      final body = jsonDecode(raw);
      if (body is! Map || body['id'] is! String) {
        throw const StudioApiException('Resposta inválida do servidor.');
      }
      return body['id'] as String;
    } on SocketException {
      throw const StudioApiException('Sem ligação ao servidor.');
    } finally {
      client.close(force: true);
    }
  }
}
class StudioApiException implements Exception {
  const StudioApiException(this.message);
  final String message;
  @override String toString() => message;
}
