#!/bin/bash

# Quick Lombok Fix Script for Linux/Mac
# This script attempts multiple solutions to fix the Lombok compilation issue

echo "=================================================================="
echo "  Friends Pharma ERP - Lombok Compilation Fix Script"
echo "=================================================================="
echo ""

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
GRAY='\033[0;37m'
NC='\033[0m' # No Color

# Set Java Home (adjust path if needed)
export JAVA_HOME="/usr/lib/jvm/java-21-openjdk"  # Common path on Linux
# export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"  # Uncomment for Mac
export PATH="$JAVA_HOME/bin:$PATH"

echo -e "${YELLOW}Step 1: Verifying Java installation...${NC}"
java -version 2>&1 | head -3
if [ $? -ne 0 ]; then
    echo -e "${RED}ERROR: Java not found. Please install JDK 21.${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Java OK${NC}"
echo ""

echo -e "${YELLOW}Step 2: Cleaning previous build artifacts...${NC}"
if [ -d "target" ]; then
    rm -rf target
    echo -e "${GREEN}✓ Removed target/${NC}"
fi
if [ -d "$HOME/.m2/repository/com/friendspharma" ]; then
    rm -rf "$HOME/.m2/repository/com/friendspharma"
    echo -e "${GREEN}✓ Removed cached artifacts${NC}"
fi
echo ""

echo -e "${YELLOW}Step 3: Attempting Maven build with delombok...${NC}"
echo -e "${GRAY}  This may take 1-2 minutes...${NC}"
mvn clean install -DskipTests -U > build-output.log 2>&1

if [ $? -eq 0 ]; then
    echo ""
    echo -e "${GREEN}==================================================================${NC}"
    echo -e "${GREEN}  ✓✓✓ SUCCESS! Build completed successfully! ✓✓✓${NC}"
    echo -e "${GREEN}==================================================================${NC}"
    echo ""
    echo -e "${CYAN}Next steps:${NC}"
    echo "  1. Configure database in src/main/resources/application.yml"
    echo "  2. Run application: mvn spring-boot:run"
    echo "  3. Access Swagger UI: http://localhost:8080/swagger-ui.html"
    echo ""
    echo -e "${GRAY}For more info, see README.md${NC}"
    exit 0
fi

echo ""
echo -e "${YELLOW}Build failed. Analyzing errors...${NC}"

# Check if it's Lombok-related
if grep -q "cannot find symbol.*get" build-output.log; then
    echo ""
    echo -e "${RED}==================================================================${NC}"
    echo -e "${RED}  Lombok annotation processing is not working in Maven CLI${NC}"
    echo -e "${RED}==================================================================${NC}"
    echo ""
    echo -e "${YELLOW}This is a known issue. Please use one of these solutions:${NC}"
    echo ""
    echo -e "${CYAN}SOLUTION 1 (RECOMMENDED - 5 minutes):${NC}"
    echo "  • Open project in IntelliJ IDEA or Eclipse"
    echo "  • Install Lombok plugin"
    echo "  • Enable annotation processing"
    echo "  • Build → Rebuild Project"
    echo "  • Run PharmaPosApplication"
    echo ""
    echo -e "${CYAN}SOLUTION 2 (Manual - 60 minutes):${NC}"
    echo "  • See LOMBOK_FIX_GUIDE.md → Solution 4"
    echo "  • Add explicit @Getter @Setter to all DTOs"
    echo ""
    echo -e "${GRAY}For detailed instructions, see:${NC}"
    echo -e "  → ${NC}LOMBOK_FIX_GUIDE.md"
    echo ""
    
    # Save errors to file for analysis
    cp build-output.log build-errors.log
    echo -e "${GRAY}Full error log saved to: build-errors.log${NC}"
else
    echo ""
    echo -e "${RED}Build failed with non-Lombok errors. Check build-errors.log${NC}"
    cp build-output.log build-errors.log
fi

echo ""
echo -e "${GRAY}Need help? Check these files:${NC}"
echo "  • LOMBOK_FIX_GUIDE.md     - Step-by-step Lombok solutions"
echo "  • IMPLEMENTATION_STATUS.md - What's been built"
echo "  • SESSION_SUMMARY.md      - Development session overview"
echo "  • README.md               - Complete setup guide"
echo ""

exit 1
