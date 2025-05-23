targetdir=target

if [ ! -d "$targetdir" ]; then mkdir $targetdir; fi

javac -sourcepath src -d $targetdir -cp lib/ECLA.jar:lib/DTNConsoleConnection.jar src/core/*.java src/movement/*.java src/report/*.java src/routing/*.java src/routing/community/*.java src/gui/*.java src/input/*.java src/applications/*.java src/interfaces/*.java

if [ ! -d "$targetdir/gui/buttonGraphics" ]; then cp -R src/gui/buttonGraphics target/gui/; fi

if [ ! -d "$targetdir/gui/playfield" ]; then
  echo "Creating directory $targetdir/gui/playfield"
  mkdir -p "$targetdir/gui/playfield"
fi

cp src/gui/playfield/oiia_cat.gif $targetdir/gui/playfield/oiia_cat.gif
