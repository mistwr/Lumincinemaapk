import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

class StoryboardScreen extends StatefulWidget {
  const StoryboardScreen({super.key});
  @override State<StoryboardScreen> createState() => _StoryboardScreenState();
}
class _StoryboardScreenState extends State<StoryboardScreen> {
  final description = TextEditingController();
  final scenes = <String>[];
  int duration = 10;
  @override void initState() { super.initState(); restore(); }
  Future<void> restore() async {
    final p = await SharedPreferences.getInstance();
    if (mounted) setState(() => scenes.addAll(p.getStringList('cinema_scenes') ?? []));
  }
  Future<void> persist() async {
    final p = await SharedPreferences.getInstance();
    await p.setStringList('cinema_scenes', scenes);
  }
  Future<void> addScene() async {
    final text = description.text.trim();
    if (text.isEmpty) return;
    setState(() => scenes.add('$duration|$text'));
    description.clear();
    await persist();
  }
  Future<void> deleteScene(int index) async {
    setState(() => scenes.removeAt(index));
    await persist();
  }
  int get totalSeconds => scenes.fold(0, (sum, s) => sum + (int.tryParse(s.split('|').first) ?? 0));
  @override void dispose() { description.dispose(); super.dispose(); }
  @override Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('LUMIN Cinema')),
    body: ListView(padding: const EdgeInsets.all(16), children: [
      Text('${scenes.length} cenas • ${totalSeconds}s', style: const TextStyle(fontSize: 22)),
      const SizedBox(height: 16),
      TextField(controller: description, maxLines: 3,
        decoration: const InputDecoration(labelText: 'Descrição da cena', border: OutlineInputBorder())),
      const SizedBox(height: 12),
      DropdownButtonFormField<int>(
        value: duration,
        decoration: const InputDecoration(labelText: 'Duração', border: OutlineInputBorder()),
        items: [5,10,15].map((n) => DropdownMenuItem(value: n, child: Text('$n segundos'))).toList(),
        onChanged: (v) => setState(() => duration = v ?? 10)),
      const SizedBox(height: 12),
      FilledButton.icon(onPressed: addScene, icon: const Icon(Icons.add), label: const Text('Adicionar cena')),
      const SizedBox(height: 16),
      for (var i=0; i<scenes.length; i++)
        Card(child: ListTile(
          leading: CircleAvatar(child: Text('${i+1}')),
          title: Text(scenes[i].substring(scenes[i].indexOf('|')+1)),
          subtitle: Text('${scenes[i].split('|').first} segundos'),
          trailing: IconButton(icon: const Icon(Icons.delete_outline),
            onPressed: () => deleteScene(i)))),
      const Text('Storyboard local. Não gera nem exporta vídeo nesta versão.',
        style: TextStyle(color: Colors.white60)),
    ]),
  );
}
