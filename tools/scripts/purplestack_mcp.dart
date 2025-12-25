import 'dart:io';
import 'dart:convert';
import 'package:archive/archive.dart';
import 'package:path/path.dart' as path;
import 'package:bm25/bm25.dart';
import 'package:mcp_dart/mcp_dart.dart';

/// Main entry point for the MCP server
void main() async {
  final server = PurpleStackMcpServer();

  try {
    await server.initialize();
    server.start();
  } catch (e) {
    stderr.writeln('Failed to initialize server: $e');
    exit(1);
  }
}

/// MCP server that serves recipes and API documentation
class PurpleStackMcpServer {
  static String get contentZipPath {
    final scriptUri = Platform.script;
    final scriptDir = path.dirname(scriptUri.toFilePath());
    return path.normalize(
      path.join(scriptDir, '..', 'content', 'mcp-content.zip'),
    );
  }

  late McpServer _server;

  // Content storage - now using full paths as keys
  final Map<String, String> _recipes = {};
  final Map<String, String> _docs = {};

  // Search indexes
  BM25? _recipeSearchIndex;
  BM25? _docSearchIndex;
  List<String> _recipePaths = [];
  List<String> _docPaths = [];

  PurpleStackMcpServer();

  /// Initialize the server and load content
  Future<void> initialize() async {
    // Create MCP server
    _server = McpServer(
      Implementation(name: 'Purplestack Context Server', version: '1.0.0'),
      options: ServerOptions(
        capabilities: ServerCapabilities(tools: ServerCapabilitiesTools()),
      ),
    );

    // Load content from zip file
    await _loadContent();

    // Build search indexes
    await _buildSearchIndexes();

    // Register tools
    _registerTools();
  }

  /// Start the MCP server
  void start() {
    _server.connect(StdioServerTransport());
  }

  /// Load content from the zip file
  Future<void> _loadContent() async {
    final file = File(contentZipPath);
    if (!file.existsSync()) {
      throw Exception('Content zip file not found: $contentZipPath');
    }

    final bytes = await file.readAsBytes();
    final archive = ZipDecoder().decodeBytes(bytes);

    for (final file in archive) {
      if (file.isFile &&
          (file.name.endsWith('.md') || file.name.endsWith('.html'))) {
        final content = utf8.decode(file.content as List<int>);

        if (file.name.startsWith('recipes/')) {
          // Use full path as key, removing only the 'recipes/' prefix
          final recipePath = file.name.substring('recipes/'.length);
          _recipes[recipePath] = content;
        } else if (file.name.startsWith('api-docs/')) {
          // Use full path as key, removing only the 'api-docs/' prefix
          final docPath = file.name.substring('api-docs/'.length);
          _docs[docPath] = content;
        }
      }
    }
  }

  /// Build search indexes for recipes and docs
  Future<void> _buildSearchIndexes() async {
    if (_recipes.isNotEmpty) {
      // Build recipe search index
      _recipePaths = _recipes.keys.toList();
      final recipeDocuments = _recipes.entries.map((entry) {
        return '${entry.key} ${entry.value}';
      }).toList();
      _recipeSearchIndex = await BM25.build(recipeDocuments);
    }

    if (_docs.isNotEmpty) {
      // Build docs search index
      _docPaths = _docs.keys.toList();
      final docDocuments = _docs.entries.map((entry) {
        return '${entry.key} ${entry.value}';
      }).toList();
      _docSearchIndex = await BM25.build(docDocuments);
    }
  }

  /// Register all tools with the MCP server
  void _registerTools() {
    // List recipes tool
    _server.tool(
      'list_recipes',
      description: 'List all available recipes',
      callback: ({args, extra}) async {
        final result = await _listRecipes({});
        return CallToolResult.fromContent(content: [TextContent(text: result)]);
      },
    );

    // Read recipe tool
    _server.tool(
      'read_recipe',
      description: 'Read a specific recipe by name',
      inputSchemaProperties: {
        'name': {'type': 'string', 'description': 'Name of the recipe to read'},
      },
      callback: ({args, extra}) async {
        final result = await _readRecipe(args ?? {});
        return CallToolResult.fromContent(content: [TextContent(text: result)]);
      },
    );

    // Search recipes tool
    _server.tool(
      'search_recipes',
      description: 'Search recipes by query',
      inputSchemaProperties: {
        'query': {'type': 'string', 'description': 'Search query for recipes'},
      },
      callback: ({args, extra}) async {
        final result = await _searchRecipes(args ?? {});
        return CallToolResult.fromContent(content: [TextContent(text: result)]);
      },
    );

    // List docs tool
    _server.tool(
      'list_docs',
      description: 'List all available documentation',
      callback: ({args, extra}) async {
        final result = await _listDocs({});
        return CallToolResult.fromContent(content: [TextContent(text: result)]);
      },
    );

    // Read doc tool
    _server.tool(
      'read_doc',
      description: 'Read a specific document by name',
      inputSchemaProperties: {
        'name': {
          'type': 'string',
          'description': 'Name of the document to read',
        },
      },
      callback: ({args, extra}) async {
        final result = await _readDoc(args ?? {});
        return CallToolResult.fromContent(content: [TextContent(text: result)]);
      },
    );

    // Search docs tool
    _server.tool(
      'search_docs',
      description: 'Search documentation by query',
      inputSchemaProperties: {
        'query': {
          'type': 'string',
          'description': 'Search query for documentation',
        },
      },
      callback: ({args, extra}) async {
        final result = await _searchDocs(args ?? {});
        return CallToolResult.fromContent(content: [TextContent(text: result)]);
      },
    );
  }

  // Tool handlers

  Future<String> _listRecipes(Map<String, dynamic> arguments) async {
    if (_recipes.isEmpty) {
      return 'No recipes available.';
    }

    final recipeList = _recipes.keys.toList()..sort();
    return 'Available recipes:\n${recipeList.map((path) => '- $path').join('\n')}';
  }

  Future<String> _readRecipe(Map<String, dynamic> arguments) async {
    final name = arguments['name'] as String?;
    if (name == null) {
      return 'Error: Recipe name is required';
    }

    // Try exact match first
    var recipe = _recipes[name];
    if (recipe != null) {
      return recipe;
    }

    // Try partial match for backwards compatibility
    final matchingKeys = _recipes.keys
        .where(
          (key) =>
              key.toLowerCase().contains(name.toLowerCase()) ||
              path.basenameWithoutExtension(key).toLowerCase() ==
                  name.toLowerCase(),
        )
        .toList();

    if (matchingKeys.length == 1) {
      return _recipes[matchingKeys.first]!;
    } else if (matchingKeys.length > 1) {
      return 'Multiple recipes found. Please be more specific:\n${matchingKeys.map((key) => '- $key').join('\n')}';
    }

    final suggestions = _findSimilarKeys(name, _recipes.keys.toList());
    final suggestionText = suggestions.isNotEmpty
        ? '\n\nDid you mean: ${suggestions.join(', ')}?'
        : '';
    return 'Recipe "$name" not found.$suggestionText';
  }

  Future<String> _searchRecipes(Map<String, dynamic> arguments) async {
    final query = arguments['query'] as String?;
    if (query == null || query.isEmpty) {
      return 'Error: Search query is required';
    }

    if (_recipeSearchIndex == null) {
      return 'Search index not available';
    }

    final results = await _recipeSearchIndex!.search(query);
    if (results.isEmpty) {
      return 'No recipes found for query: "$query"';
    }

    // Build documents list for index lookup
    final recipeDocuments = _recipes.entries.map((entry) {
      return '${entry.key} ${entry.value}';
    }).toList();

    final resultText = StringBuffer('Search results for "$query":\n\n');
    for (final result in results.take(5)) {
      final index = recipeDocuments.indexOf(result.doc.text);
      if (index != -1) {
        final recipePath = _recipePaths[index];
        final score = result.score.toStringAsFixed(2);
        resultText.writeln('$recipePath ($score)');
      }
    }

    return resultText.toString().trim();
  }

  Future<String> _listDocs(Map<String, dynamic> arguments) async {
    if (_docs.isEmpty) {
      return 'No documentation available.';
    }

    final docList = _docs.keys.toList()..sort();
    return 'Available documentation:\n${docList.map((path) => '- $path').join('\n')}';
  }

  Future<String> _readDoc(Map<String, dynamic> arguments) async {
    final name = arguments['name'] as String?;
    if (name == null) {
      return 'Error: Document name is required';
    }

    // Try exact match first
    var doc = _docs[name];
    if (doc != null) {
      return doc;
    }

    // Try partial match for backwards compatibility
    final matchingKeys = _docs.keys
        .where(
          (key) =>
              key.toLowerCase().contains(name.toLowerCase()) ||
              path.basenameWithoutExtension(key).toLowerCase() ==
                  name.toLowerCase(),
        )
        .toList();

    if (matchingKeys.length == 1) {
      return _docs[matchingKeys.first]!;
    } else if (matchingKeys.length > 1) {
      return 'Multiple documents found. Please be more specific:\n${matchingKeys.map((key) => '- $key').join('\n')}';
    }

    final suggestions = _findSimilarKeys(name, _docs.keys.toList());
    final suggestionText = suggestions.isNotEmpty
        ? '\n\nDid you mean: ${suggestions.join(', ')}?'
        : '';
    return 'Document "$name" not found.$suggestionText';
  }

  Future<String> _searchDocs(Map<String, dynamic> arguments) async {
    final query = arguments['query'] as String?;
    if (query == null || query.isEmpty) {
      return 'Error: Search query is required';
    }

    if (_docSearchIndex == null) {
      return 'Search index not available';
    }

    final results = await _docSearchIndex!.search(query);
    if (results.isEmpty) {
      return 'No documentation found for query: "$query"';
    }

    // Build documents list for index lookup
    final docDocuments = _docs.entries.map((entry) {
      return '${entry.key} ${entry.value}';
    }).toList();

    final resultText = StringBuffer('Search results for "$query":\n\n');
    for (final result in results.take(5)) {
      final index = docDocuments.indexOf(result.doc.text);
      if (index != -1) {
        final docPath = _docPaths[index];
        final score = result.score.toStringAsFixed(2);
        resultText.writeln('$docPath ($score)');
      }
    }

    return resultText.toString().trim();
  }

  /// Find similar keys for suggestions
  List<String> _findSimilarKeys(String input, List<String> keys) {
    final inputLower = input.toLowerCase();
    return keys
        .where(
          (key) =>
              key.toLowerCase().contains(inputLower) ||
              inputLower.contains(key.toLowerCase()) ||
              path
                  .basenameWithoutExtension(key)
                  .toLowerCase()
                  .contains(inputLower),
        )
        .take(3)
        .toList();
  }
}
