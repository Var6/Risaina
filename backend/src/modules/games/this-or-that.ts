import type { QuizItem } from '../../db/types.js';
import type { DeckId } from './catalog.js';
import { cryptoRandom, type RandomInt, sampleAvoiding } from './random.js';

export interface ThisOrThatPrompt {
  id: string;
  deck: Exclude<DeckId, 'mix'>;
  a: string;
  b: string;
}

const pairs = (deck: ThisOrThatPrompt['deck'], prefix: string, list: [string, string][]) =>
  list.map(([a, b], i) => ({ id: `${prefix}-${String(i + 1).padStart(2, '0')}`, deck, a, b }));

/**
 * Curated, PG and warm. Written in-house (no licence obligations). Ids are stable: they are stored
 * in games and used to avoid repeating prompts from the couple's recent rounds, so never renumber;
 * append new pairs at the end of a deck.
 */
export const THIS_OR_THAT_PROMPTS: readonly ThisOrThatPrompt[] = [
  ...pairs('everyday', 'ev', [
    ['🍵 Tea', '☕ Coffee'],
    ['🌅 Morning person', '🌙 Night owl'],
    ['📞 Call', '💬 Text'],
    ['🍫 Sweet', '🥨 Salty'],
    ['🌧️ Rainy day', '☀️ Sunny day'],
    ['📚 Books', '🎬 Movies'],
    ['🛁 Bath', '🚿 Shower'],
    ['🎧 Podcast', '🎵 Music'],
    ['🧦 Socks on in bed', '🦶 Socks off in bed'],
    ['🍳 Big breakfast', '🥐 Quick bite'],
    ['🏠 Stay in', '🎉 Go out'],
    ['☀️ Summer', '❄️ Winter'],
    ['⏰ Snooze', '🛏️ Up at once'],
    ['🎮 Video games', '🧩 Puzzles'],
    ['🧹 Tidy now', '🗂️ Tidy later'],
    ['🍦 Ice cream', '🎂 Cake'],
    ['🌶️ Spicy', '🧈 Mild'],
    ['🏃 Workout', '🛋️ Nap'],
    ['📸 Photos', '🎥 Videos'],
    ['🎤 Sing', '💃 Dance'],
    ['🥶 A bit too cold', '🥵 A bit too warm'],
    ['📝 Make a list', '🌀 Wing it'],
  ]),
  ...pairs('us', 'us', [
    ['🍳 Breakfast in bed', '🍝 Dinner out'],
    ['💌 Love letter', '🎶 Love song'],
    ['🗺️ Plan the date', '🎁 Be surprised'],
    ['💃 Slow dance', '🕺 Silly dance'],
    ['📞 One long call', '💬 Texts all day'],
    ['🎬 Movie night', '🎲 Game night'],
    ['🤗 Long hug', '😘 Lots of kisses'],
    ['🌅 Sunrise together', '🌇 Sunset together'],
    ['🏖️ Lazy weekend', '🧗 Adventure weekend'],
    ['📸 Posed selfies', '🖼️ Candid photos'],
    ['🎂 Big birthday', '🕯️ Quiet birthday'],
    ['🧺 Picnic', '🍽️ Restaurant'],
    ['💐 Flowers', '🍫 Chocolates'],
    ['🎧 Same playlist', '📖 Same book'],
    ['🛋️ Cuddle on the couch', '🚶 Walk and talk'],
    ['🍪 Bake together', '🍲 Cook together'],
    ['🗓️ Count down the days', '🎉 Surprise visit'],
    ['🌙 Fall asleep on call', '📴 Say goodnight and hang up'],
    ['🎁 Handmade gift', '🛍️ Bought gift'],
    ['🎤 Sing to me', '📜 Write to me'],
    ['💭 Dream about the future', '😂 Laugh about the past'],
    ['👕 Borrowed hoodie', '🧸 Matching pyjamas'],
  ]),
  ...pairs('travel_food', 'tf', [
    ['🏖️ Beach', '🏔️ Mountains'],
    ['🚗 Road trip', '✈️ Flight'],
    ['🌮 Street food', '🍽️ Fine dining'],
    ['🍛 Biryani', '🍕 Pizza'],
    ['🏙️ City break', '🌲 Forest cabin'],
    ['🚆 Night train', '⛴️ Ferry'],
    ['🎒 Backpack', '🧳 Suitcase'],
    ['🗺️ Plan every day', '🎲 Go with the flow'],
    ['🏨 Hotel', '⛺ Camping'],
    ['🥘 Try the local dish', '🍔 Stick to favourites'],
    ['🍜 Ramen', '🍣 Sushi'],
    ['🥭 Mango', '🍓 Strawberry'],
    ['🍿 Popcorn', '🍟 Fries'],
    ['🥾 Hiking day', '🏛️ Museum day'],
    ['🧁 Cupcake', '🍩 Doughnut'],
    ['🥞 Pancakes', '🧇 Waffles'],
    ['☕ Café hopping', '🛍️ Market hopping'],
    ['🪟 Window seat', '🚶 Aisle seat'],
    ['🏝️ Island', '🏰 Old town'],
    ['🍦 Gelato', '🥤 Milkshake'],
    ['🫖 Chai', '🧃 Fresh juice'],
    ['🍲 Home cooking', '🥡 Takeaway'],
  ]),
  ...pairs('dreams', 'dr', [
    ['🏙️ City flat', '🏡 Countryside house'],
    ['🐶 A dog', '🐱 A cat'],
    ['🌊 House by the sea', '🏔️ Cabin in the hills'],
    ['🚐 Van life', '🛳️ Cruise'],
    ['🎨 Learn to paint', '🎸 Learn guitar'],
    ['🌍 See the world', '🏠 Build a home'],
    ['🍳 Big kitchen', '🛁 Big bathtub'],
    ['🌱 Garden', '🏊 Pool'],
    ['🗼 Paris', '🗾 Tokyo'],
    ['🧘 Slow life', '🚀 Big adventures'],
    ['⛷️ Ski trip', '🤿 Diving trip'],
    ['📚 Home library', '🎮 Game room'],
    ['🌌 Northern lights', '🌸 Cherry blossoms'],
    ['🏕️ A month off-grid', '🏙️ A month in New York'],
    ['🐎 Learn to ride', '⛵ Learn to sail'],
    ['🎤 Be famous', '🤫 Be secretly rich'],
    ['✍️ Write a book', '🎬 Make a film'],
    ['🦁 Safari', '🐧 Antarctica'],
    ['🕰️ Visit the past', '🔮 Visit the future'],
    ['☕ Open a café', '📖 Open a bookshop'],
    ['🦸 Fly', '👻 Be invisible'],
    ['🏝️ Retire on an island', '🏘️ Retire near family'],
  ]),
];

/**
 * `count` prompts from a deck ('mix' = every deck), avoiding the ids in `avoid` (prompts from the
 * couple's recent rounds) whenever the deck has enough others.
 */
export function pickPrompts(
  deck: DeckId,
  count: number,
  avoid: ReadonlySet<string>,
  random: RandomInt = cryptoRandom,
): QuizItem[] {
  const pool = deck === 'mix' ? THIS_OR_THAT_PROMPTS : THIS_OR_THAT_PROMPTS.filter((p) => p.deck === deck);
  return sampleAvoiding(pool, count, avoid, (p) => p.id, random).map((p) => ({
    id: p.id,
    text: null,
    choices: [p.a, p.b],
    correct: null,
    category: null,
    difficulty: null,
  }));
}
