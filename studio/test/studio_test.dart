import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:lumin_ai_studio/main.dart';
import 'package:lumin_ai_studio/storyboard.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() => SharedPreferences.setMockInitialValues({}));
  testWidgets('home and cinema editor are accessible', (tester) async {
    await tester.pumpWidget(const LuminStudio());
    await tester.pumpAndSettle();
    expect(find.text('A tua criatividade, sem limites'), findsOneWidget);
    await tester.ensureVisible(find.text('LUMIN Cinema'));
    await tester.tap(find.text('LUMIN Cinema'));
    await tester.pumpAndSettle();
    expect(find.text('Adicionar cena'), findsOneWidget);
    expect(find.text('0 cenas • 0s'), findsOneWidget);
  });
  testWidgets('can save a scene and update duration', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: StoryboardTestHost()));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'Uma cidade futurista');
    await tester.tap(find.text('Adicionar cena'));
    await tester.pumpAndSettle();
    expect(find.text('1 cenas • 10s'), findsOneWidget);
    expect(find.text('Uma cidade futurista'), findsOneWidget);
  });
}
class StoryboardTestHost extends StatelessWidget {
  const StoryboardTestHost({super.key});
  @override Widget build(BuildContext context) => const StoryboardScreen();
}
