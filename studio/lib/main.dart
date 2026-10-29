import 'storyboard.dart';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() => runApp(const LuminStudio());
const gold = Color(0xFFE3BA60);
class LuminStudio extends StatelessWidget {
  const LuminStudio({super.key});
  @override Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'LUMIN AI Studio',
    theme: ThemeData.dark().copyWith(
      scaffoldBackgroundColor: const Color(0xFF0B0C11),
      colorScheme: const ColorScheme.dark(primary: gold),
      appBarTheme: const AppBarTheme(backgroundColor: Color(0xFF0B0C11)),
    ),
    home: const StudioHome(),
  );
}
class StudioHome extends StatefulWidget {
  const StudioHome({super.key});
  @override State<StudioHome> createState() => _StudioHomeState();
}
class _StudioHomeState extends State<StudioHome> {
  int tab = 0;
  String kind = 'Imagem';
  String format = '9:16';
  final prompt = TextEditingController();
  List<String> drafts = [];
  @override void initState() { super.initState(); load(); }
  Future<void> load() async {
    final prefs = await SharedPreferences.getInstance();
    if(mounted) setState(() => drafts = prefs.getStringList('drafts') ?? []);
  }
  Future<void> save() async {
    if(prompt.text.trim().isEmpty) return;
    final item = '$kind • $format • ${prompt.text.trim()}';
    setState(() { drafts.insert(0, item); tab = 2; });
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList('drafts', drafts);
    prompt.clear();
  }
  Future<void> remove(int i) async {
    setState(() => drafts.removeAt(i));
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList('drafts', drafts);
  }
  Widget tile(String title, String subtitle, IconData icon, VoidCallback onTap) =>
    Card(color: const Color(0xFF1A1B23), child: ListTile(
      contentPadding: const EdgeInsets.all(18),
      leading: Icon(icon, color: gold, size: 32),
      title: Text(title, style: const TextStyle(fontWeight: FontWeight.bold)),
      subtitle: Text(subtitle), trailing: const Icon(Icons.chevron_right),
      onTap: onTap));
  Widget home() => ListView(padding: const EdgeInsets.all(16), children: [
    const Text('A tua criatividade, sem limites', style: TextStyle(fontSize: 25, fontWeight: FontWeight.bold)),
    const SizedBox(height: 12),
    const Text('LUMIN AI STUDIO', style: TextStyle(color: gold, letterSpacing: 3)),
    const SizedBox(height: 18),
    tile('Imagem IA', 'Cria um conceito visual', Icons.image, () => setState(() {kind='Imagem';tab=1;})),
    tile('Vídeo IA', 'Prepara um vídeo cinematográfico', Icons.movie_creation, () => setState(() {kind='Vídeo';tab=1;})),
    tile('LUMIN Cinema', 'Editor de cenas e storyboards', Icons.theaters, () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const StoryboardScreen()))),
    const SizedBox(height: 16),
    const Text('Os pedidos são guardados localmente. Geração real ainda não ligada.',
      style: TextStyle(color: Colors.white60)),
  ]);
  Widget create() => ListView(padding: const EdgeInsets.all(18), children: [
    const Text('Criar projeto', style: TextStyle(fontSize: 26, fontWeight: FontWeight.bold)),
    const SizedBox(height: 16),
    DropdownButtonFormField<String>(
      value: kind, decoration: const InputDecoration(labelText: 'Ferramenta', border: OutlineInputBorder()),
      items: ['Imagem','Vídeo','Cinema'].map((v) => DropdownMenuItem(value:v, child:Text(v))).toList(),
      onChanged: (v) => setState(() => kind=v!)),
    const SizedBox(height: 14),
    DropdownButtonFormField<String>(
      value: format, decoration: const InputDecoration(labelText: 'Formato', border: OutlineInputBorder()),
      items: ['9:16','16:9','1:1'].map((v) => DropdownMenuItem(value:v, child:Text(v))).toList(),
      onChanged: (v) => setState(() => format=v!)),
    const SizedBox(height: 14),
    TextField(controller: prompt, maxLines: 6,
      decoration: const InputDecoration(labelText: 'Descreve a tua ideia', alignLabelWithHint:true, border: OutlineInputBorder())),
    const SizedBox(height: 18),
    FilledButton.icon(onPressed: save, icon: const Icon(Icons.save), label: const Text('Guardar projeto')),
    const SizedBox(height: 12),
    const Text('Não é executada qualquer geração por IA nesta versão.',
      style: TextStyle(color: Colors.white60)),
  ]);
  Widget works() => drafts.isEmpty
    ? const Center(child: Text('Ainda não tens projetos guardados'))
    : ListView.builder(itemCount:drafts.length, itemBuilder:(context,i) =>
      Card(color: const Color(0xFF1A1B23), child: ListTile(
        title:Text(drafts[i]), trailing: IconButton(
          icon: const Icon(Icons.delete_outline, color:gold), onPressed:() => remove(i)))));
  @override Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('✦ LUMIN AI STUDIO')),
    body: IndexedStack(index:tab, children:[home(),create(),works()]),
    bottomNavigationBar: NavigationBar(
      selectedIndex:tab, onDestinationSelected:(i)=>setState(()=>tab=i),
      destinations: const [
        NavigationDestination(icon:Icon(Icons.home_outlined),label:'Início'),
        NavigationDestination(icon:Icon(Icons.add_circle_outline),label:'Criar'),
        NavigationDestination(icon:Icon(Icons.collections_bookmark_outlined),label:'Obras'),
      ]),
  );
}
