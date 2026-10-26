import type { TriviaDifficulty } from '../../db/types.js';

export interface FallbackQuestion {
  id: string;
  /** The curated OpenTDB category id it belongs to (see catalog.ts), so a requested category still fits. */
  categoryId: number;
  category: string;
  difficulty: TriviaDifficulty;
  text: string;
  correct: string;
  wrong: [string, string, string];
}

type Row = [TriviaDifficulty, string, string, [string, string, string]];

const bank = (prefix: string, categoryId: number, category: string, rows: Row[]): FallbackQuestion[] =>
  rows.map(([difficulty, text, correct, wrong], i) => ({
    id: `tb_${prefix}${String(i + 1).padStart(2, '0')}`,
    categoryId,
    category,
    difficulty,
    text,
    correct,
    wrong,
  }));

/**
 * The offline question set, used when Open Trivia DB is slow, down or rate-limited, so a duel
 * always starts. Written in-house (no licence obligation, no attribution needed) and limited to
 * well-established facts. Ids are stable (stored in games, used to avoid repeats): append only.
 */
export const TRIVIA_FALLBACK: readonly FallbackQuestion[] = [
  ...bank('gk', 9, 'General knowledge', [
    ['easy', 'How many days are there in a leap year?', '366', ['365', '364', '367']],
    ['easy', 'Which colour do you get by mixing blue and yellow?', 'Green', ['Purple', 'Orange', 'Brown']],
    ['easy', 'How many sides does a hexagon have?', 'Six', ['Five', 'Seven', 'Eight']],
    ['medium', 'How many minutes are there in a day?', '1,440', ['1,240', '1,140', '1,640']],
    [
      'medium',
      'Which language has the most native speakers in the world?',
      'Mandarin Chinese',
      ['Spanish', 'English', 'Hindi'],
    ],
    ['easy', 'What is the currency of Japan?', 'Yen', ['Won', 'Yuan', 'Ringgit']],
    ['easy', 'Which month has the fewest days?', 'February', ['April', 'June', 'November']],
    ['easy', 'In which city is the Taj Mahal?', 'Agra', ['Delhi', 'Jaipur', 'Lucknow']],
    ['easy', 'How many colours are there in a rainbow?', 'Seven', ['Six', 'Five', 'Eight']],
    ['medium', 'What is the hardest natural substance?', 'Diamond', ['Gold', 'Iron', 'Quartz']],
    ['medium', 'What is the smallest prime number?', 'Two', ['One', 'Three', 'Zero']],
  ]),
  ...bank('fm', 11, 'Film', [
    [
      'easy',
      'Which film series features the line "May the Force be with you"?',
      'Star Wars',
      ['Star Trek', 'Dune', 'Avatar'],
    ],
    [
      'easy',
      'In "The Lion King", what is the name of Simba\'s father?',
      'Mufasa',
      ['Scar', 'Rafiki', 'Zazu'],
    ],
    [
      'easy',
      'Who directed "Jurassic Park" (1993)?',
      'Steven Spielberg',
      ['James Cameron', 'George Lucas', 'Ridley Scott'],
    ],
    [
      'hard',
      'Which film won the very first Academy Award for Best Picture?',
      'Wings',
      ['Sunrise', 'The Jazz Singer', 'Metropolis'],
    ],
    ['easy', 'What is the name of the cowboy toy in "Toy Story"?', 'Woody', ['Buzz', 'Jessie', 'Andy']],
    [
      'easy',
      'Which 1997 film is set aboard a famous ship that sank in 1912?',
      'Titanic',
      ['Poseidon', 'Lusitania', 'Britannic'],
    ],
    [
      'medium',
      'In the 1939 film "The Wizard of Oz", what are Dorothy\'s slippers made of?',
      'Rubies',
      ['Silver', 'Emeralds', 'Glass'],
    ],
    [
      'easy',
      'Which actor played Captain Jack Sparrow in "Pirates of the Caribbean"?',
      'Johnny Depp',
      ['Orlando Bloom', 'Brad Pitt', 'Keanu Reeves'],
    ],
    [
      'easy',
      'Bollywood is the Hindi film industry of which country?',
      'India',
      ['Pakistan', 'Bangladesh', 'Nepal'],
    ],
    ['easy', 'What kind of animal is Dumbo?', 'An elephant', ['A mouse', 'A deer', 'A bear']],
    [
      'medium',
      'Which animation studio made "Spirited Away"?',
      'Studio Ghibli',
      ['Pixar', 'DreamWorks', 'Toei Animation'],
    ],
  ]),
  ...bank('mu', 12, 'Music', [
    ['easy', 'How many strings does a standard guitar have?', 'Six', ['Four', 'Five', 'Twelve']],
    ['easy', 'Which band released "Hey Jude"?', 'The Beatles', ['The Rolling Stones', 'Queen', 'The Who']],
    ['medium', 'Who composed the "Moonlight Sonata"?', 'Beethoven', ['Mozart', 'Bach', 'Chopin']],
    ['medium', 'How many keys does a standard piano have?', '88', ['76', '92', '64']],
    [
      'easy',
      'Freddie Mercury was the lead singer of which band?',
      'Queen',
      ['Genesis', 'Led Zeppelin', 'Pink Floyd'],
    ],
    [
      'hard',
      'Which classical instrument usually has 47 strings and seven pedals?',
      'Concert harp',
      ['Cello', 'Sitar', 'Harpsichord'],
    ],
    [
      'medium',
      'Which Italian music term means "very loud"?',
      'Fortissimo',
      ['Pianissimo', 'Allegro', 'Adagio'],
    ],
    [
      'easy',
      'Who is known as the "King of Pop"?',
      'Michael Jackson',
      ['Elvis Presley', 'Prince', 'Stevie Wonder'],
    ],
    [
      'easy',
      'The sitar is most closely linked with the classical music of which country?',
      'India',
      ['Japan', 'Turkey', 'Egypt'],
    ],
    ['medium', 'How many lines does a standard musical staff have?', 'Five', ['Four', 'Six', 'Seven']],
    [
      'medium',
      'In which decade was "Bohemian Rhapsody" first released?',
      'The 1970s',
      ['The 1960s', 'The 1980s', 'The 1990s'],
    ],
  ]),
  ...bank('sc', 17, 'Science & nature', [
    ['easy', 'What is the chemical symbol for gold?', 'Au', ['Ag', 'Gd', 'Go']],
    ['easy', 'Which planet is known as the Red Planet?', 'Mars', ['Venus', 'Jupiter', 'Mercury']],
    [
      'easy',
      'Which gas do plants take in from the air to make food?',
      'Carbon dioxide',
      ['Oxygen', 'Nitrogen', 'Hydrogen'],
    ],
    ['medium', 'How many bones are there in an adult human body?', '206', ['196', '216', '226']],
    ['easy', 'What is the largest planet in our solar system?', 'Jupiter', ['Saturn', 'Neptune', 'Earth']],
    ['easy', 'At sea level, at what temperature does water boil?', '100 °C', ['90 °C', '110 °C', '120 °C']],
    [
      'medium',
      "What is the most common gas in Earth's atmosphere?",
      'Nitrogen',
      ['Oxygen', 'Carbon dioxide', 'Argon'],
    ],
    [
      'medium',
      'Which part of a cell holds most of its genetic material?',
      'The nucleus',
      ['The membrane', 'The ribosome', 'The cytoplasm'],
    ],
    ['easy', 'Which planet is famous for its bright, wide rings?', 'Saturn', ['Mars', 'Mercury', 'Venus']],
    [
      'hard',
      'Roughly how fast does light travel in a vacuum?',
      'About 300,000 km per second',
      ['About 30,000 km per second', 'About 3,000 km per second', 'About 3 million km per second'],
    ],
    ['easy', 'H2O is the chemical formula for what?', 'Water', ['Salt', 'Ozone', 'Sugar']],
  ]),
  ...bank('ge', 22, 'Geography', [
    ['medium', 'What is the capital of Australia?', 'Canberra', ['Sydney', 'Melbourne', 'Perth']],
    ['easy', 'What is the longest river in Africa?', 'The Nile', ['The Congo', 'The Niger', 'The Zambezi']],
    [
      'medium',
      'Mount Everest sits on the border between Nepal and which other country?',
      'China',
      ['India', 'Bhutan', 'Pakistan'],
    ],
    [
      'easy',
      'What is the largest ocean on Earth?',
      'The Pacific',
      ['The Atlantic', 'The Indian', 'The Arctic'],
    ],
    ['easy', 'What is the capital of Canada?', 'Ottawa', ['Toronto', 'Vancouver', 'Montreal']],
    [
      'medium',
      'What is the largest hot desert in the world?',
      'The Sahara',
      ['The Gobi', 'The Kalahari', 'The Thar'],
    ],
    ['easy', 'In which country is Machu Picchu?', 'Peru', ['Chile', 'Bolivia', 'Mexico']],
    ['easy', 'What is the capital of Japan?', 'Tokyo', ['Kyoto', 'Osaka', 'Hiroshima']],
    ['easy', 'Which river flows through Paris?', 'The Seine', ['The Thames', 'The Rhine', 'The Danube']],
    [
      'medium',
      'What is the smallest country in the world by area?',
      'Vatican City',
      ['Monaco', 'San Marino', 'Malta'],
    ],
    ['medium', 'What is the capital of Kenya?', 'Nairobi', ['Mombasa', 'Kampala', 'Addis Ababa']],
  ]),
  ...bank('hi', 23, 'History', [
    ['easy', 'In which year did people first land on the Moon?', '1969', ['1965', '1972', '1959']],
    [
      'easy',
      'Who was the first President of the United States?',
      'George Washington',
      ['Abraham Lincoln', 'Thomas Jefferson', 'John Adams'],
    ],
    ['easy', 'In which year did India become independent?', '1947', ['1950', '1945', '1942']],
    [
      'medium',
      'Which civilisation built Machu Picchu?',
      'The Inca',
      ['The Maya', 'The Aztecs', 'The Olmecs'],
    ],
    ['medium', 'In which year did the Berlin Wall fall?', '1989', ['1991', '1987', '1985']],
    [
      'medium',
      'Who was the first woman to win a Nobel Prize?',
      'Marie Curie',
      ['Florence Nightingale', 'Ada Lovelace', 'Rosalind Franklin'],
    ],
    [
      'easy',
      'Which empire built the Colosseum in Rome?',
      'The Roman Empire',
      ['The Ottoman Empire', 'The Persian Empire', 'The Mongol Empire'],
    ],
    [
      'medium',
      'Which ship carried the Pilgrims to America in 1620?',
      'The Mayflower',
      ['The Santa Maria', 'The Beagle', 'The Endeavour'],
    ],
    ['easy', 'Who painted the Mona Lisa?', 'Leonardo da Vinci', ['Michelangelo', 'Raphael', 'Rembrandt']],
    ['easy', 'The Great Wall was built mainly in which country?', 'China', ['Mongolia', 'Japan', 'Korea']],
    ['medium', 'Which Mughal emperor built the Taj Mahal?', 'Shah Jahan', ['Akbar', 'Babur', 'Aurangzeb']],
  ]),
  ...bank('an', 27, 'Animals', [
    [
      'easy',
      'What is the largest animal on Earth?',
      'The blue whale',
      ['The African elephant', 'The giraffe', 'The orca'],
    ],
    ['easy', 'How many legs does a spider have?', 'Eight', ['Six', 'Ten', 'Twelve']],
    ['easy', 'What is a baby kangaroo called?', 'A joey', ['A kit', 'A cub', 'A pup']],
    ['easy', 'What is the fastest land animal?', 'The cheetah', ['The lion', 'The horse', 'The greyhound']],
    ['easy', 'What do giant pandas mostly eat?', 'Bamboo', ['Eucalyptus', 'Fish', 'Berries']],
    ['medium', 'How many hearts does an octopus have?', 'Three', ['One', 'Two', 'Four']],
    [
      'easy',
      'Which animal is called the "ship of the desert"?',
      'The camel',
      ['The horse', 'The donkey', 'The llama'],
    ],
    ['easy', 'What is a group of lions called?', 'A pride', ['A pack', 'A herd', 'A flock']],
  ]),
  ...bank('sp', 21, 'Sports', [
    [
      'easy',
      'How many players does a football (soccer) team have on the pitch?',
      'Eleven',
      ['Ten', 'Nine', 'Twelve'],
    ],
    ['easy', 'In tennis, what is a score of zero called?', 'Love', ['Nil', 'Duck', 'Nought']],
    ['easy', 'How many rings are on the Olympic flag?', 'Five', ['Four', 'Six', 'Seven']],
    ['easy', 'In cricket, how many balls are in a standard over?', 'Six', ['Five', 'Eight', 'Ten']],
    [
      'easy',
      'In which sport do players make a slam dunk?',
      'Basketball',
      ['Volleyball', 'Tennis', 'Handball'],
    ],
    [
      'medium',
      'Roughly how long is a marathon?',
      'About 42 km',
      ['About 21 km', 'About 50 km', 'About 35 km'],
    ],
    [
      'medium',
      'Which country hosted the first modern Olympic Games in 1896?',
      'Greece',
      ['France', 'The United Kingdom', 'The United States'],
    ],
    [
      'medium',
      'How many points is a touchdown worth in American football?',
      'Six',
      ['Three', 'Seven', 'Five'],
    ],
  ]),
];
